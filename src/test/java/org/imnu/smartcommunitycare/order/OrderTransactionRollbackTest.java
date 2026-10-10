package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderLogMapper;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrderLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.mybatis.spring.SqlSessionTemplate;
import org.apache.ibatis.session.SqlSessionFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-06 / B-07 / B-09：<b>失败必须整体回滚</b>，不允许留下部分提交。
 *
 * <p>原工单（第 109、110、112 行）：
 * <ul>
 *   <li><b>B-06</b> 占位之后，订单插入失败 → 名额与订单、日志恢复到操作前</li>
 *   <li><b>B-07</b> 订单已插入但必要创建日志写入失败 → 名额、订单均回滚，
 *       不留下「没日志的成功订单」</li>
 *   <li><b>B-09</b> 取消的必要日志写入失败 → 订单保持取消前状态，
 *       名额未释放，没有部分提交</li>
 * </ul>
 *
 * <h3>故障注入为什么用委派代理而不是 Mockito</h3>
 * MyBatis 的 Mapper 是 JDK 动态代理，接口方法在 Mockito 看来是抽象的，
 * {@code callRealMethod()} 会抛
 * {@code Cannot call abstract real method on java object}。
 * 若改用 {@code @MockitoBean} 整体替换，则<b>该 Mapper 的所有方法都是 mock</b>——
 * 而原工单明确要求「其他数据库操作保持真实」。
 *
 * <p>因此这里用本类内的 {@code FaultInjectionConfig} 注册一个委派代理：
 * 容器里注入的是一个 JDK 委派代理，<b>除指定方法外全部转给真实 Mapper</b>，
 * 被指定的方法在调用前先让测试有机会抛错。故障点因此是精确的，
 * 名额更新、订单查询、日志查询都仍是真实 SQL。
 *
 * <p><b>没有新增生产故障参数</b>（原工单明令禁止 {@code fail=true} 之类），
 * 也没有跳过鉴权或模拟全部 Mapper 的成功结果。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderTransactionRollbackTest extends BaseIntegrationTest {

    private static final String ELDER = "it_rollback_elder";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceSlotMapper slotMapper;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private FaultPoint faultPoint;

    /**
     * 真实订单 Mapper，用于测试自己读回持久状态做前后比对。
     *
     * <p><b>刻意不靠 {@code @Autowired} 按类型注入</b>：本类注册了
     * {@code @Primary} 的故障感知代理，按类型注入拿到的会是那个代理，
     * 「读回状态」就成了经过被测路径的读。改用与配置类相同的
     * {@code SqlSessionTemplate.getMapper} 方式拿干净的。
     */
    private ServiceOrderMapper realOrderMapper;

    private ServiceOrderLogMapper realLogMapper;

    private String elderToken;
    private Long serviceId;

    @BeforeEach
    void prepare() throws Exception {
        realOrderMapper = FaultInjectionConfig.realMapper(ServiceOrderMapper.class, context);
        realLogMapper = FaultInjectionConfig.realMapper(ServiceOrderLogMapper.class, context);

        createElder(ELDER, ELDER_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);

        serviceId = createFixtureItem(it -> {
            it.setName("回滚测试服务");
            it.setDescription("用于验证订单事务回滚");
            it.setPrice(new BigDecimal("80.00"));
            it.setPhone("13700000000");
        });
    }

    @AfterEach
    void clearFault() {
        // 必须恢复：注入若残留，会让后续用例莫名其妙地失败。
        // 单独一个 @AfterEach 而不是 try-finally，是为了失败时也能看清。
        faultPoint.clear();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"assign,PENDING_ASSIGNMENT", "submit-result,IN_SERVICE", "confirm,PENDING_CONFIRMATION"})
    void fulfillmentLogFailureRollsBackEntireAction(String action, String state) throws Exception {
        createAccount("it_rollback_admin", ADMIN_PASSWORD, org.imnu.smartcommunitycare.user.model.enums.UserRole.COMMUNITY_ADMIN, true);
        Long staff = createAccount("it_rollback_staff", STAFF_PASSWORD, org.imnu.smartcommunitycare.user.model.enums.UserRole.SERVICE_STAFF, true);
        String admin = loginAndGetToken(mockMvc, "it_rollback_admin", ADMIN_PASSWORD);
        String worker = loginAndGetToken(mockMvc, "it_rollback_staff", STAFF_PASSWORD);
        long slot = createSlot(3);
        String id = bookAndGetOrderId(slot, UUID.randomUUID().toString());
        jdbcTemplate.update("UPDATE service_order SET status=?,staff_id=? WHERE id=?", state, staff, id);
        Map<String,Object> before = jdbcTemplate.queryForMap("SELECT * FROM service_order WHERE id=?", id);
        int logs = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=?", Integer.class, id);
        faultPoint.failOnNext(ServiceOrderLogMapper.class, "insert", new IllegalStateException("履约必要日志故障夹具"));
        String body = "{\"version\":0,\"staffId\":\"" + staff + "\",\"reason\":\"登记原因\",\"result\":\"登记结果\"}";
        String token = action.equals("assign") ? admin : action.equals("confirm") ? elderToken : worker;
        mockMvc.perform(post("/api/service-orders/"+id+"/"+action).header("scc-test-token","Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isInternalServerError());
        assertThat(faultPoint.attemptsOf(ServiceOrderLogMapper.class,"insert")).isPositive();
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM service_order WHERE id=?", id)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=?", Integer.class, id)).isEqualTo(logs);
        assertThat(occupiedOf(slot)).isEqualTo(1);
    }

    // ==================== B-06 ====================

    /**
     * B-06：占位之后订单插入失败 → 事务回滚，名额与订单、日志恢复到操作前。
     *
     * <p><b>故障点</b>：{@code ServiceOrderMapper.insert}。
     * 此时名额已经在同一事务里被 {@code occupySlot} 扣掉了，
     * 所以这个用例真正验证的是「已扣的名额会不会随事务一起回滚」。
     *
     * <p><b>为什么必须证明故障点确实到达</b>：若注入没生效，请求会正常成功 200，
     * 断言「订单数为 0」反而会因为「本来就没订单」而通过——
     * 那就是假通过。这里用 {@code insertAttempts} 计数把故障点钉死。
     */
    @Test
    @DisplayName("B-06 订单插入失败：名额、订单、日志全部回滚到操作前")
    void orderInsertFailureRollsBackSlotOrderAndLog() throws Exception {
        long slotId = createSlot(3);

        // 操作前基线
        assertThat(occupiedOf(slotId)).isZero();
        assertThat(orderCountOf(slotId)).isZero();

        faultPoint.failOnNext(ServiceOrderMapper.class, "insert",
                new IllegalStateException("注入故障：订单插入失败"));

        int status = bookStatus(slotId, UUID.randomUUID().toString());

        // 故障点确实到达——否则下面的断言全是假通过
        assertThat(faultPoint.attemptsOf(ServiceOrderMapper.class, "insert"))
                .as("注入的 insert 故障必须真的被触发过")
                .isPositive();

        // 请求必须失败，且不能是 4xx 业务拒绝（那是另一回事）
        assertThat(status)
                .as("订单插入失败时请求必须失败，实际：%d", status)
                .isGreaterThanOrEqualTo(500);

        // 核心：名额回到操作前。若事务没回滚，这里会是 1。
        assertThat(occupiedOf(slotId))
                .as("订单插入失败后名额必须回滚，实际：%d", occupiedOf(slotId))
                .isZero();
        assertThat(orderCountOf(slotId)).as("不应留下订单").isZero();
        assertThat(createLogCount(slotId)).as("不应留下创建日志").isZero();

        // 没有负名额
        assertThat(occupiedOf(slotId)).isGreaterThanOrEqualTo(0);
    }

    /**
     * B-06 补充：失败之后同一时段仍可正常预约。
     *
     * <p>只断言「回滚了」不够——万一回滚把时段弄成了不可用状态，
     * 数据虽然自洽，业务却坏了。这里证明回滚后时段是<b>可继续使用</b>的。
     */
    @Test
    @DisplayName("B-06补充 回滚后同一时段仍可正常预约，不残留禁用或满额状态")
    void slotStaysUsableAfterRollback() throws Exception {
        long slotId = createSlot(2);

        faultPoint.failOnNext(ServiceOrderMapper.class, "insert",
                new IllegalStateException("注入故障：订单插入失败"));
        assertThat(bookStatus(slotId, UUID.randomUUID().toString()))
                .as("注入故障时请求应失败").isGreaterThanOrEqualTo(500);
        assertThat(faultPoint.attemptsOf(ServiceOrderMapper.class, "insert")).isPositive();

        // 恢复后真实建单一次，必须成功
        String orderId = bookAndGetOrderId(slotId, UUID.randomUUID().toString());
        assertThat(orderId).isNotBlank();
        assertThat(occupiedOf(slotId)).isEqualTo(1);
        assertThat(orderCountOf(slotId)).isEqualTo(1);
        assertThat(createLogCount(slotId)).isEqualTo(1);

        ServiceSlot slot = slotMapper.selectById(slotId);
        assertThat(slot.getEnabled()).as("回滚不应关闭时段").isTrue();
        assertThat(slot.getCapacity()).as("回滚不应改动容量").isEqualTo(2);
    }

    // ==================== B-07 ====================

    /**
     * B-07：订单已插入但必要创建日志写入失败 → 名额、订单均回滚。
     *
     * <p><b>这是三个回滚场景里最能暴露问题的一个</b>：
     * 订单 insert 已经成功，如果不回滚，库里就会留下一条
     * 「占了名额、状态正常、却没有创建日志」的订单——
     * 时间线上看不出它是何时因何而来的。
     *
     * <p>故障点打在 {@code ServiceOrderLogMapper.insert}。
     */
    @Test
    @DisplayName("B-07 创建日志写入失败：订单与名额一并回滚，不留无日志订单")
    void createLogFailureRollsBackOrderAndSlot() throws Exception {
        long slotId = createSlot(3);

        faultPoint.failOnNext(ServiceOrderLogMapper.class, "insert",
                new IllegalStateException("注入故障：创建日志写入失败"));

        int status = bookStatus(slotId, UUID.randomUUID().toString());

        assertThat(faultPoint.attemptsOf(ServiceOrderLogMapper.class, "insert"))
                .as("注入的日志故障必须真的被触发过")
                .isPositive();
        assertThat(status)
                .as("日志写入失败时请求必须失败，实际：%d", status)
                .isGreaterThanOrEqualTo(500);

        // 订单 insert 已成功过，但必须被回滚掉
        assertThat(orderCountOf(slotId))
                .as("日志失败时订单必须一并回滚，实际留下：%d 单", orderCountOf(slotId))
                .isZero();
        assertThat(occupiedOf(slotId))
                .as("日志失败时名额必须回滚，实际：%d", occupiedOf(slotId))
                .isZero();
        assertThat(createLogCount(slotId)).isZero();
    }

    /**
     * B-07 补充：失败后重新提交同一 requestId，必须能正常建单。
     *
     * <p>验证回滚是<b>彻底</b>的：不能留下「占着名额的幽灵」，
     * 也不能让后续请求被那次失败的残留挡住。
     */
    @Test
    @DisplayName("B-07补充 日志失败后重放同一requestId可正常建单")
    void replayAfterLogFailureSucceeds() throws Exception {
        long slotId = createSlot(2);
        String requestId = UUID.randomUUID().toString();

        faultPoint.failOnNext(ServiceOrderLogMapper.class, "insert",
                new IllegalStateException("注入故障：创建日志写入失败"));
        assertThat(bookStatus(slotId, requestId)).isGreaterThanOrEqualTo(500);
        assertThat(faultPoint.attemptsOf(ServiceOrderLogMapper.class, "insert")).isPositive();

        // 同一把键重放：失败的那次没留下任何东西，所以这次应当真正建单
        String orderId = bookAndGetOrderId(slotId, requestId);
        assertThat(orderId).isNotBlank();

        assertThat(occupiedOf(slotId)).isEqualTo(1);
        assertThat(orderCountOf(slotId)).isEqualTo(1);
        assertThat(createLogCount(slotId)).isEqualTo(1);

        ServiceOrder order = realOrderMapper.selectById(Long.valueOf(orderId));
        assertThat(order).isNotNull();
        assertThat(order.getRequestId()).isEqualTo(requestId);
    }

    // ==================== B-09 ====================

    /**
     * B-09：取消的必要日志写入失败 → 订单保持取消前状态，名额未释放。
     *
     * <p><b>取消路径的三步都在同一事务里</b>：
     * 条件改状态 → 释放名额 → 写 CANCEL 日志。
     * 日志失败必须把前两步一起回滚，否则会出现
     * 「订单还是 PENDING_ASSIGNMENT，名额却被释放了」——
     * 少占一个名额，看起来能多接一单，实际超发。
     */
    @Test
    @DisplayName("B-09 取消日志写入失败：订单状态与名额都保持取消前，无部分提交")
    void cancelLogFailureKeepsOrderAndSlotUnchanged() throws Exception {
        long slotId = createSlot(3);
        String requestId = UUID.randomUUID().toString();

        // 先真实建单成功
        String orderId = bookAndGetOrderId(slotId, requestId);
        Long orderKey = Long.valueOf(orderId);
        assertThat(occupiedOf(slotId)).isEqualTo(1);

        ServiceOrder before = realOrderMapper.selectById(orderKey);
        assertThat(before.getStatus()).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(before.getCancelledAt()).isNull();
        int versionBefore = before.getVersion();
        int cancelLogsBefore = cancelLogCountOf(orderId);

        // 注入：取消的日志写入失败
        faultPoint.failOnNext(ServiceOrderLogMapper.class, "insert",
                new IllegalStateException("注入故障：取消日志写入失败"));

        int status = cancelStatus(orderId);

        assertThat(faultPoint.attemptsOf(ServiceOrderLogMapper.class, "insert"))
                .as("注入的取消日志故障必须真的被触发过")
                .isPositive();
        assertThat(status)
                .as("取消日志失败时请求必须失败，实际：%d", status)
                .isGreaterThanOrEqualTo(500);

        // 订单保持取消前状态
        ServiceOrder after = realOrderMapper.selectById(orderKey);
        assertThat(after.getStatus())
                .as("取消失败后订单状态不得改变")
                .isEqualTo("PENDING_ASSIGNMENT");
        assertThat(after.getCancelledAt())
                .as("取消失败后不得有取消时间")
                .isNull();
        assertThat(after.getVersion())
                .as("取消失败后版本不得推进")
                .isEqualTo(versionBefore);
        assertThat(after.getCancelReason()).isNull();

        // 名额未释放——这是最关键的一条，释放了就等于超发
        assertThat(occupiedOf(slotId))
                .as("取消失败后名额不得释放，实际：%d", occupiedOf(slotId))
                .isEqualTo(1);

        // 日志不得留下
        assertThat(cancelLogCountOf(orderId))
                .as("取消失败后不得留下 CANCEL 日志")
                .isEqualTo(cancelLogsBefore);
        assertThat(createLogCount(slotId)).isEqualTo(1);
    }

    /**
     * B-09 补充：取消失败后可以正常取消。
     *
     * <p>与 B-06 补充同理：回滚不仅要「退回去」，还得「退干净」——
     * 不能让那次失败把订单卡在无法取消的状态。
     */
    @Test
    @DisplayName("B-09补充 取消失败后可正常取消，名额与日志最终一致")
    void cancelSucceedsAfterInjectedFailure() throws Exception {
        long slotId = createSlot(2);
        String orderId = bookAndGetOrderId(slotId, UUID.randomUUID().toString());
        Long orderKey = Long.valueOf(orderId);

        faultPoint.failOnNext(ServiceOrderLogMapper.class, "insert",
                new IllegalStateException("注入故障：取消日志写入失败"));
        assertThat(cancelStatus(orderId)).isGreaterThanOrEqualTo(500);
        assertThat(faultPoint.attemptsOf(ServiceOrderLogMapper.class, "insert")).isPositive();

        // 恢复后真实取消一次
        assertThat(cancelStatus(orderId)).as("恢复后取消应成功").isEqualTo(200);

        ServiceOrder after = realOrderMapper.selectById(orderKey);
        assertThat(after.getStatus()).isEqualTo("CANCELLED");
        assertThat(after.getCancelledAt()).isNotNull();
        assertThat(occupiedOf(slotId)).isEqualTo(0);
        assertThat(cancelLogCountOf(orderId)).isEqualTo(1);
    }

    // ==================== 夹具与辅助 ====================

    /**
     * 建一个未来时段，返回其真实 ID。
     *
     * <p>用 {@code ServiceSlotMapper.insert} 让 MyBatis-Plus 回填主键，
     * 不用 {@code SELECT LAST_INSERT_ID()}——那依赖「上条 update 与本条 select
     * 同一连接」，经连接池时并不成立，可能拿到别的连接上的值。
     */
    private Long createSlot(int capacity) {
        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(serviceId);
        slot.setStartAt(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        slot.setEndAt(LocalDateTime.now().plusDays(1).withHour(12).withMinute(0));
        slot.setCapacity(capacity);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(slot);
        if (slot.getId() == null) {
            throw new IllegalStateException("时段插入后未回填主键");
        }
        return slot.getId();
    }

    private String createBody(Long slotId, String requestId) {
        return """
                {"slotId":%d,"contactName":"王秀英","contactPhone":"13600000005",
                 "address":"回滚测试地址","requestId":"%s"}
                """.formatted(slotId, requestId);
    }

    private int bookStatus(Long slotId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slotId, requestId)))
                .andReturn().getResponse().getStatus();
    }

    private String bookAndGetOrderId(Long slotId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(slotId, requestId)))
                .andExpect(status().isOk())
                .andReturn();
        var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?")
                .matcher(result.getResponse().getContentAsString());
        if (!matcher.find()) {
            throw new IllegalStateException("响应中未找到订单ID："
                    + result.getResponse().getContentAsString());
        }
        return matcher.group(1);
    }

    private int cancelStatus(String orderId) throws Exception {
        return mockMvc.perform(post("/api/service-orders/" + orderId + "/cancel")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withOrderVersion(orderId, """
                                {"reason":"回滚测试取消"}
                                """)))
                .andReturn().getResponse().getStatus();
    }

    private int occupiedOf(Long slotId) {
        return jdbcTemplate.queryForObject(
                "SELECT occupied_count FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private int orderCountOf(Long slotId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_order WHERE slot_id = ?", Integer.class, slotId);
    }

    private int createLogCount(Long slotId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM service_order_log log
                JOIN service_order o ON o.id = log.order_id
                WHERE o.slot_id = ? AND log.action = 'CREATE'
                """, Integer.class, slotId);
    }

    private int cancelLogCountOf(String orderId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_order_log WHERE order_id = ? AND action = 'CANCEL'",
                Integer.class, orderId);
        return n == null ? 0 : n;
    }

    // ==================== 故障注入设施 ====================

    /**
     * 故障点登记表：记录「哪个 Mapper 的哪个方法下一次要抛错」。
     *
     * <p>方法名用 {@code ClassName#methodName} 记，避免在测试类里持有
     * Class 对象造成不必要的耦合。
     */
    static class FaultPoint {

        private final AtomicReference<String> armedKey = new AtomicReference<>();
        private final AtomicReference<RuntimeException> armedError = new AtomicReference<>();
        private final Map<String, AtomicInteger> attempts = new java.util.concurrent.ConcurrentHashMap<>();

        /** 安排：下一个 {@code type#method} 调用抛错，只生效一次 */
        void failOnNext(Class<?> mapperType, String methodName, RuntimeException error) {
            armedKey.set(mapperType.getSimpleName() + "#" + methodName);
            armedError.set(error);
        }

        void clear() {
            armedKey.set(null);
            armedError.set(null);
            attempts.clear();
        }

        /** 该方法被调用过几次（含被拦截的那次） */
        int attemptsOf(Class<?> mapperType, String methodName) {
            AtomicInteger counter = attempts.get(mapperType.getSimpleName() + "#" + methodName);
            return counter == null ? 0 : counter.get();
        }

        /**
         * 在真实调用之前判断是否要抛错。
         *
         * @return 要抛的异常；不需要抛时返回 null，调用方继续走真实逻辑
         */
        RuntimeException checkAndConsume(String typeName, String methodName) {
            String key = typeName + "#" + methodName;
            attempts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
            String registeredKey = armedKey.get();
            // CAS 按对象身份比较，必须传入刚读取的引用，不能传内容相同的新字符串。
            if (key.equals(registeredKey) && armedKey.compareAndSet(registeredKey, null)) {
                RuntimeException error = armedError.getAndSet(null);
                if (error != null) {
                    return error;
                }
            }
            return null;
        }
    }

    /**
     * 把两个订单 Mapper 换成「只在指定方法上抛错、其余全部委派真实实现」的代理。
     *
     * <p>用 {@code @Primary} 让注入方（{@code OrderCreator} 等生产 Bean）拿到代理。
     * 测试自己要读回持久状态时<b>不走按类型注入</b>，
     * 而是用 {@link #realMapper} 现场造一个干净的，否则「读状态」就成了经过被测路径的读。
     *
     * <p>真实 Mapper 在首次调用时从应用已有的 SqlSessionFactory 获取，
     * 避免故障代理构建时提前初始化其依赖。
     *
     * <p><b>必须用 {@code @TestConfiguration} 而不是 {@code @Configuration}</b>：
     * 内嵌 {@code @Configuration} 会被 Spring Test 当成<b>独立的</b>配置来源，
     * {@code @SpringBootTest} 因此找不到主类
     * （{@code SmartCommunityCareApplication}），上下文里只剩这里声明的两个 Bean，
     * 表现为连基类的 {@code userMapper} 都注入失败。
     * {@code @TestConfiguration} 则被自动识别为「测试专用补充配置」，
     * 与主配置合并。
     */
    @TestConfiguration
    static class FaultInjectionConfig {

        @Bean
        FaultPoint faultPoint() {
            return new FaultPoint();
        }

        @Bean
        @Primary
        ServiceOrderMapper faultAwareOrderMapper(ApplicationContext context, FaultPoint faultPoint) {
            return delegating(ServiceOrderMapper.class, context, faultPoint);
        }

        @Bean
        @Primary
        ServiceOrderLogMapper faultAwareLogMapper(ApplicationContext context, FaultPoint faultPoint) {
            return delegating(ServiceOrderLogMapper.class, context, faultPoint);
        }

        /**
         * 造一个委派代理：指定方法按故障表抛错，其余<b>全部转给真实 Mapper</b>。
         *
         * <p>原工单要求「其他数据库操作保持真实」，所以不能用
         * {@code @MockitoBean} 整体替换——那会让该 Mapper 的所有方法都变成 mock，
         * 名额更新、状态查询就都是假的了。
         *
         * <p>真实 Mapper 首次调用时获取并缓存，SqlSessionTemplate 参与现有 Spring 事务。
         */
        @SuppressWarnings("unchecked")
        private static <T> T delegating(Class<T> mapperType, ApplicationContext context,
                                        FaultPoint faultPoint) {
            String typeName = mapperType.getSimpleName();
            // 首次调用时获取，后续复用同一个真实 Mapper。
            final java.util.concurrent.atomic.AtomicReference<T> realRef =
                    new java.util.concurrent.atomic.AtomicReference<>();

            InvocationHandler handler = new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    RuntimeException toThrow =
                            faultPoint.checkAndConsume(typeName, method.getName());
                    if (toThrow != null) {
                        throw toThrow;
                    }
                    T real = realRef.get();
                    if (real == null) {
                        real = realMapper(mapperType, context);
                        realRef.compareAndSet(null, real);
                        real = realRef.get();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            };
            return (T) Proxy.newProxyInstance(
                    mapperType.getClassLoader(), new Class<?>[]{mapperType}, handler);
        }

        /** 获取本应用已有的 SqlSessionFactory，不新建数据源或会话工厂。 */
        private static SqlSessionFactory sqlSessionFactory(ApplicationContext context) {
            return (SqlSessionFactory) context.getBean("sqlSessionFactory");
        }

        /** 从原会话工厂获取真实 Mapper，绕过故障包装但保留事务参与。 */
        static <T> T realMapper(Class<T> mapperType, ApplicationContext context) {
            return new SqlSessionTemplate(sqlSessionFactory(context)).getMapper(mapperType);
        }
    }
}
