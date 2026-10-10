package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 验证容量调整的顺序规则以及预约与目录维护竞争下的不变量。
 * 固定锁与提交先后的场景由OrderLockOrderCompetitionTest覆盖；同时起跑的压力测试不证明具体提交次序。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCompetitionTest extends BaseIntegrationTest {

    private static final String ELDER = "it_comp_elder";
    private static final String ADMIN = "it_comp_admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String elderToken;
    private String adminToken;
    private Long serviceId;
    private Long slotId;

    @BeforeEach
    void prepare() throws Exception {
        createElder(ELDER, ELDER_PASSWORD);
        // 管理员必须真的创建出来，否则 loginAndGetToken 拿不到令牌，
        // 后续管理请求会全部 401
        createAccount(ADMIN, ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);
        adminToken = loginAndGetToken(mockMvc, ADMIN, ADMIN_PASSWORD);

        serviceId = createFixtureItem(it -> {
            it.setName("竞争测试服务");
            it.setDescription("用于验证管理操作与预约的竞争");
            it.setPrice(new BigDecimal("70.00"));
            it.setPhone("13900000000");
        });
        slotId = createSlot(3);
    }

    // ==================== 一、容量调整 ====================

    @Test
    @DisplayName("B-10a 容量调小：不得低于已占用名额")
    void capacityCannotShrinkBelowOccupied() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(200, bookStatus(), "第 " + (i + 1) + " 笔应成功");
        }
        assertEquals(3, occupiedOf(slotId));

        // 改到 1，小于已占用 3
        assertEquals(409, adminPut("/api/services/slots/" + slotId + "/capacity", "capacity", "1"),
                "容量小于已占用名额时应被拒绝");

        // 改到 3，等于已占用，应当允许
        assertEquals(200, adminPut("/api/services/slots/" + slotId + "/capacity", "capacity", "3"));
    }

    @Test
    @DisplayName("B-10b 容量调大后可用名额增加")
    void capacityGrowAllowsMoreBooking() throws Exception {
        assertEquals(3, capacityOf(slotId));
        // 先占满：容量 3、占用 0 时第一笔当然成功，占满后才该被拒。
        // （原写法误把「容量 3」当成「已满」，是断言写错，不是代码问题）
        for (int i = 0; i < 3; i++) {
            assertEquals(200, bookStatus(), "第 " + (i + 1) + " 笔应成功");
        }
        assertEquals(409, bookStatus(), "占满 3 个后新预约应被容量拒绝");

        assertEquals(200, adminPut("/api/services/slots/" + slotId + "/capacity", "capacity", "5"));

        assertEquals(200, bookStatus(), "扩容到 5 后应能继续预约");
        assertEquals(4, occupiedOf(slotId), "扩容后占用应为 4");
        assertEquals(4, orderCountOf(slotId), "订单数应与占用一致");
    }

    // ==================== 二、时段关闭 ====================

    @Test
    @DisplayName("B-10c 关闭时段后：该时段的新预约被拒绝")
    void bookingAfterCloseRejected() throws Exception {
        assertEquals(200, adminPut("/api/services/slots/" + slotId + "/close", null, null));

        // 关闭后：业务拒绝（400），不是容量冲突
        assertEquals(400, bookStatus(),
                "时段已关闭时新预约应被明确拒绝");
    }

    @Test
    @DisplayName("B-10d 并发关闭与建单：不出现名额超扣，成功数与订单数一致")
    void concurrentCloseDuringBookingKeepsInvariants() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String rid = UUID.randomUUID().toString();
            tasks.add(() -> performBook(bookBody(slotId, rid)));
        }
        tasks.add(() -> adminPut("/api/services/slots/" + slotId + "/close", null, null));

        List<Integer> results = runConcurrently(tasks);

        // 最后一项是管理请求，它返回 200，不能混进建单结果里统计
        int closeStatus = results.get(results.size() - 1);
        List<Integer> bookResults = results.subList(0, results.size() - 1);
        assertNoServerError(closeStatus, "关闭时段");
        assertEquals(200, closeStatus, "关闭时段请求本身应成功");

        long ok = bookResults.stream().filter(r -> r == 200).count();
        long rejected = bookResults.stream().filter(r -> r == 400 || r == 409).count();
        assertEquals(8, ok + rejected,
                "8 笔建单都应有明确结局（200/400/409），实际：" + bookResults);

        assertTrue(occupiedOf(slotId) <= capacityOf(slotId), "占用不应超过容量");
        assertEquals((int) ok, orderCountOf(slotId), "成功数应与实际订单数一致");
    }

    // ==================== 三、服务下架 ====================

    @Test
    @DisplayName("B-10e 下架服务后：该服务的新预约被拒绝")
    void bookingAfterDisableRejected() throws Exception {
        assertEquals(200, adminPut("/api/services/" + serviceId + "/disable", null, null));

        assertEquals(400, bookStatus(), "服务已下架时其时段不应接受新预约");
    }

    /**
     * 并发下架与建单。
     *
     * <p><b>本用例断言的是不变量，不是「绝不下架后建单」。</b>
     * 原工单第 111 行说得很直接：「不能把已先成功受理的预约，
     * 因随后下架就认定为『下架后新增』」。同时起跑时无法判定先后，
     * 所以「下架后仍有订单」既可能是缺陷，也可能是合法的先受理——
     * 断言它必然不发生只会得到 flaky 失败。
     *
     * <p>真正该断言的是：无论交错如何，<b>名额不超扣、订单数与成功数一致</b>。
     *
     * <p><b>有确定先后的同类场景</b>（下架先提交 / 建单先受理，
     * 两种顺序各断言一次）在 {@link OrderLockOrderCompetitionTest}，
     * 那里用同步闸把锁与提交的先后固定下来。
     *
     * <p><b>更正</b>：本用例原注释称「读-判-用窗口的修复属于改动建单主流程，
     * 本批只核实不修」——该修复<b>早已完成</b>（建单侧已加服务行锁），
     * 保留这句话会让人误以为缺陷仍在。
     */
    @Test
    @DisplayName("B-10f 并发下架与建单：不出现名额超扣，成功数与订单数一致")
    void concurrentDisableDuringBookingKeepsInvariants() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String rid = UUID.randomUUID().toString();
            tasks.add(() -> performBook(bookBody(slotId, rid)));
        }
        tasks.add(() -> adminPut("/api/services/" + serviceId + "/disable", null, null));

        List<Integer> results = runConcurrently(tasks);

        int disableStatus = results.get(results.size() - 1);
        List<Integer> bookResults = results.subList(0, results.size() - 1);
        assertNoServerError(disableStatus, "下架服务");
        assertEquals(200, disableStatus, "下架服务请求本身应成功");

        long ok = bookResults.stream().filter(r -> r == 200).count();
        long rejected = bookResults.stream().filter(r -> r == 400 || r == 409).count();
        assertEquals(8, ok + rejected,
                "8 笔建单都应有明确结局（200/400/409），实际：" + bookResults);

        assertTrue(occupiedOf(slotId) <= capacityOf(slotId), "占用不应超过容量");
        assertEquals((int) ok, orderCountOf(slotId), "成功数应与订单数一致");
    }

    /**
     * 把建单与服务下架的先后顺序固定下来，验证「先下架后建单」被拒。
     *
     * <p>这条是对上面那个窗口的<b>串行对照</b>：
     * 同样是「服务已下架」，只是顺序确定，因此结果唯一可断言。
     * 它与 {@link #bookingAfterDisableRejected} 是同一个断言，
     * 保留在此是为了把「串行安全 / 并发有窗口」这个对比摆在同一处。
     */
    @Test
    @DisplayName("B-10g 串行对照：下架先提交时，建单被拒")
    void serialDisableThenBookingRejected() throws Exception {
        adminPut("/api/services/" + serviceId + "/disable", null, null);
        assertEquals(400, bookStatus(), "下架已提交后建单必须被拒");
    }

    // ==================== 夹具与辅助 ====================

/**
     * 建一个未来时段，返回其真实 ID。
     *
     * <p>用 {@code ServiceSlotMapper.insert} 让 MyBatis-Plus 回填主键。
     *
     * <p><b>不再用 {@code SELECT LAST_INSERT_ID()}</b>：
     * 它返回<b>当前连接</b>上一次自增的值，而 {@code JdbcTemplate} 的
     * {@code update} 与 {@code queryForObject} 从连接池取连接，
     * <b>两次不保证是同一条</b>。全量并发时就会拿到别的连接上的值，
     * 表现为「时段容量不对」「订单建到别人的时段上」这类极难定位的失败。
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

    private String bookBody(Long targetSlotId, String requestId) {
        return """
                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                 "address":"竞争测试地址","requestId":"%s"}
                """.formatted(targetSlotId, requestId);
    }

    /** 提交预约并返回状态码，不抛异常 */
    private int bookStatus() throws Exception {
        return performBook(bookBody(slotId, UUID.randomUUID().toString()));
    }

    private int performBook(String body) throws Exception {
        return mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    /**
     * 管理员操作，返回状态码。
     *
     * @param paramName 查询参数名；容量接口用 {@code capacity}
     */
    private int adminPut(String url, String paramName, String paramValue) throws Exception {
        // paramName / paramValue 必须是两个独立参数：
        // updateCapacity 用的是 @RequestParam int capacity，
        // 把 "capacity=1" 整串当 paramValue 传过去，Spring 收不到名为 capacity 的参数，
        // 直接抛 MissingServletRequestParameterException → 500。
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req =
                put(url).header("scc-test-token", "Bearer " + adminToken);
        if (paramName != null) {
            req = req.param(paramName, paramValue);
        }
        return mockMvc.perform(req).andReturn().getResponse().getStatus();
    }

    /**
     * 断言管理请求没有落到 5xx。
     *
     * <p>把「服务端错误」与「断言预期不符」分开：本项目出现过一次并发下的
     * 数据库死锁（受害者是 {@code lockSlot} 的 {@code findByIdForUpdate}），
     * 它以 500 撞在断言上，报出来却是「expected 200 but was 500」，
     * 看起来像断言写错，实际是底层并发缺陷。这里给出能指对方向的提示。
     */
    private void assertNoServerError(int code, String what) {
        if (code >= 500) {
            throw new IllegalStateException(
                    what + "请求返回 " + code + "：这是服务端错误（并发下可能是数据库死锁），"
                            + "不是断言预期不符。请查后端日志是否有 "
                            + "DeadlockLoserDataAccessException");
        }
    }

    private int occupiedOf(Long targetSlot) {
        return db.queryForObject(
                "SELECT occupied_count FROM service_slot WHERE id = ?", Integer.class, targetSlot);
    }

    private int capacityOf(Long targetSlot) {
        return db.queryForObject(
                "SELECT capacity FROM service_slot WHERE id = ?", Integer.class, targetSlot);
    }

    private int orderCountOf(Long targetSlot) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM service_order WHERE slot_id = ?", Integer.class, targetSlot);
    }

    /** 并发执行所有任务并收集结果；某任务失败不影响其他任务 */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size() + 2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}