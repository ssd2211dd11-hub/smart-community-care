package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * B-11：跨老人对象权限。
 *
 * <p>老人只能操作<b>自己名下</b>的订单。
 * 归属校验在实现侧分散在三处且写法不同
 * （{@code requireOwnedOrder} / {@code requireOwnOrder} / 评价方法内的内联判断），
 * 因此逐个动作验证，不靠「一处覆盖推断全部动作都安全」。
 *
 * <p>此前已有断言：详情 404（{@code OrderBookingTest}）、
 * 确认被拒（{@code OrderExecutionTest}）。本类补齐<b>取消</b>与<b>评价</b>。
 *
 * <p>预期形态统一为 <b>404 而非 403</b>：
 * 返回 403 等于告诉对方「这个对象存在，只是你不能动」，
 * 泄露了其他老人的订单是否存在。
 *
 * <p>每个「被拒」用例都同时断言<b>没有留下痕迹</b>——
 * 只看状态码不够，拒绝路径若误改了状态或名额，同样是缺陷。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCrossElderTest extends BaseIntegrationTest {

    private static final String OWNER = "it_cross_owner";
    private static final String INTRUDER = "it_cross_intruder";

    /** 从任意响应里取第一个 id 字段（建单响应用） */
    private static final Pattern ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String ownerToken;
    private String intruderToken;
    private Long slotId;

    @BeforeEach
    void prepare() throws Exception {
        createElder(OWNER, ELDER_PASSWORD);
        createElder(INTRUDER, ELDER_PASSWORD);
        ownerToken = loginAndGetToken(mockMvc, OWNER, ELDER_PASSWORD);
        intruderToken = loginAndGetToken(mockMvc, INTRUDER, ELDER_PASSWORD);

        Long serviceId = createFixtureItem(it -> {
            it.setName("跨老人测试服务");
            it.setDescription("用于验证对象权限边界");
            it.setPrice(new BigDecimal("45.00"));
            it.setPhone("13900000000");
        });
        slotId = createSlot(serviceId, 5);
    }

    // ==================== 详情 ====================

    @Test
    @DisplayName("B-11a 他人订单详情返回 404，不泄露订单是否存在")
    void otherElderCannotReadMyOrder() throws Exception {
        Long orderId = bookAsOwner();

        assertEquals(404, status("GET", "/api/service-orders/" + orderId, intruderToken, null),
                "他人订单详情应返回 404");
        assertEquals(200, status("GET", "/api/service-orders/" + orderId, ownerToken, null),
                "本人应能读到自己的订单");
    }

    @Test
    @DisplayName("B-11b 他人订单列表中不出现我的订单（按解析出的订单ID精确判断）")
    void otherElderListHidesMyOrder() throws Exception {
        Long orderId = bookAsOwner();
        // 明确给入侵者也建一笔，否则「列表为空」也能让断言通过——
        // 那条路证明的是「过滤掉了全部」，不是「按归属过滤」。
        Long intruderOwnOrderId = bookAs(intruderToken);

        List<Long> intruderSees = orderIdsIn(
                body("GET", "/api/service-orders", intruderToken, null));
        assertFalse(intruderSees.contains(orderId),
                "他人订单列表不应出现我的订单 " + orderId + "，实际返回：" + intruderSees);
        assertTrue(intruderSees.contains(intruderOwnOrderId),
                "本人订单列表应包含自己的订单 " + intruderOwnOrderId
                        + "，否则本用例证明的是「列表恒空」而非「按归属过滤」");

        List<Long> ownerSees = orderIdsIn(
                body("GET", "/api/service-orders", ownerToken, null));
        assertTrue(ownerSees.contains(orderId),
                "本人订单列表应包含自己的订单 " + orderId);
        assertFalse(ownerSees.contains(intruderOwnOrderId),
                "owner 列表不应出现 intruder 的订单 " + intruderOwnOrderId);
    }

    // ==================== 取消 ====================

    @Test
    @DisplayName("B-11c 他人不能取消我的订单：404，状态、名额与必要日志均不变")
    void otherElderCannotCancelMyOrder() throws Exception {
        Long orderId = bookAsOwner();
        int occupiedBefore = occupied();
        // 原工单第 113 行要求「状态、名额和日志不变」。
        // 此前只断言了前两项，日志没查——拒绝路径若误写了 CANCEL 日志，
        // 订单时间线上就会多出一条不存在的操作记录。
        int cancelLogsBefore = cancelLogCount(orderId);
        assertEquals(0, cancelLogsBefore, "前置条件：建单后不应有 CANCEL 日志");

        int code = status("POST", "/api/service-orders/" + orderId + "/cancel", intruderToken,
                """
                        {"reason":"我要取消别人的订单","version":0}""");
        assertEquals(404, code, "取消他人订单应返回 404");

        // 拒绝不得留下痕迹
        assertEquals("PENDING_ASSIGNMENT", statusOf(orderId), "订单状态不应改变");
        assertEquals(occupiedBefore, occupied(), "名额不应被释放");
        assertEquals(cancelLogsBefore, cancelLogCount(orderId),
                "被拒的取消不得留下 CANCEL 日志");
        assertEquals(1, createLogCount(orderId), "原有 CREATE 日志应仍在且未被改写");
    }

    @Test
    @DisplayName("B-11d 本人取消成功（对照）")
    void ownerCanCancelOwnOrder() throws Exception {
        Long orderId = bookAsOwner();
        int occupiedBefore = occupied();

        int code = status("POST", "/api/service-orders/" + orderId + "/cancel", ownerToken,
                """
                        {"reason":"临时有事","version":0}""");
        assertEquals(200, code, "本人应能取消自己的订单");
        assertEquals("CANCELLED", statusOf(orderId));
        assertEquals(occupiedBefore - 1, occupied(), "取消应释放一个名额");
    }

    // ==================== 评价 ====================

    @Test
    @DisplayName("B-11e 他人不能评价我的订单：404，且不产生评价记录")
    void otherElderCannotReviewMyOrder() throws Exception {
        Long orderId = bookAsOwner();
        // 先置为已完成，否则请求会先撞状态检查，测不到归属校验
        markCompleted(orderId);
        int reviewsBefore = reviewCount();

        int code = status("POST", "/api/service-orders/" + orderId + "/review", intruderToken,
                """
                        {"rating":1,"content":"别人的订单"}""");
        assertEquals(404, code, "评价他人订单应返回 404");
        assertEquals(reviewsBefore, reviewCount(), "被拒的评价不得留下记录");
    }

    @Test
    @DisplayName("B-11f 本人评价成功（对照）")
    void ownerCanReviewOwnOrder() throws Exception {
        Long orderId = bookAsOwner();
        markCompleted(orderId);
        int reviewsBefore = reviewCount();

        int code = status("POST", "/api/service-orders/" + orderId + "/review", ownerToken,
                """
                        {"rating":5,"content":"服务不错"}""");
        assertEquals(200, code, "本人应能评价自己的订单");
        assertEquals(reviewsBefore + 1, reviewCount(), "应新增一条评价");
    }

    // ==================== 辅助 ====================

    /** 以 owner 身份建一笔订单，返回订单 ID */
    private Long bookAsOwner() throws Exception {
        return bookAs(ownerToken);
    }

    /** 以指定令牌建一笔订单，返回订单 ID */
    private Long bookAs(String token) throws Exception {
        String payload = """
                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                 "address":"本人地址","requestId":"%s"}
                """.formatted(slotId, UUID.randomUUID().toString());
        String resp = body("POST", "/api/service-orders", token, payload);
        Matcher m = ID_PATTERN.matcher(resp);
        assertTrue(m.find(), "建单响应里应含订单 ID：" + resp);
        return Long.valueOf(m.group(1));
    }

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
    private Long createSlot(Long serviceId, int capacity) {
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

    /** 使用项目现有 Jackson 3 解析 data 数组，不依赖字段顺序或联系人文本。 */
    private List<Long> orderIdsIn(String listResponse) {
        JsonNode data = JSON.readTree(listResponse).path("data");
        assertTrue(data.isArray(), "订单列表响应的 data 必须为数组");
        List<Long> ids = new ArrayList<>();
        for (JsonNode order : data) {
            // 身份 ID 的 JSON 契约是字符串（技术方案 §9.3），兼容数字形态。
            // 这里只保证「能取到正确 ID」，类型由 IdStringContractTest 负责断言。
            JsonNode idNode = order.path("id");
            assertTrue(idNode.isIntegralNumber()
                            || (idNode.isTextual() && idNode.asText().matches("\\d+")),
                    "订单必须返回可解析的 id，实际：" + idNode);
            ids.add(idNode.isTextual() ? Long.parseLong(idNode.asText()) : idNode.asLong());
        }
        return ids;
    }

    private int cancelLogCount(Long orderId) {
        Integer n = db.queryForObject(
                "SELECT COUNT(*) FROM service_order_log WHERE order_id = ? AND action = 'CANCEL'",
                Integer.class, orderId);
        return n == null ? 0 : n;
    }

    private int createLogCount(Long orderId) {
        Integer n = db.queryForObject(
                "SELECT COUNT(*) FROM service_order_log WHERE order_id = ? AND action = 'CREATE'",
                Integer.class, orderId);
        return n == null ? 0 : n;
    }

    /** 以指定令牌请求某个 URL，返回 HTTP 状态码；payload 为 null 表示无请求体 */
    private int status(String method, String url, String token, String payload) throws Exception {
        return perform(method, url, token, payload).getStatus();
    }

    /** 同上，返回响应体 */
    private String body(String method, String url, String token, String payload) throws Exception {
        return perform(method, url, token, payload).getContentAsString();
    }

    /** 统一发起请求，响应对象交给调用方按需取状态码或响应体 */
    private org.springframework.mock.web.MockHttpServletResponse perform(
            String method, String url, String token, String payload) throws Exception {
        var req = ("POST".equals(method) ? post(url) : get(url))
                .header("scc-test-token", "Bearer " + token);
        if (payload != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(payload);
        }
        return mockMvc.perform(req).andReturn().getResponse();
    }

    private int occupied() {
        return db.queryForObject(
                "SELECT occupied_count FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private String statusOf(Long orderId) {
        return db.queryForObject(
                "SELECT status FROM service_order WHERE id = ?", String.class, orderId);
    }

    private int reviewCount() {
        return db.queryForObject("SELECT COUNT(*) FROM service_review", Integer.class);
    }

    /**
     * 把订单直接置为已完成。
     *
     * <p>直接改库而不走履约接口：走接口要依次开工、提交，
     * 本类只关心「已完成之后他人能否评价」，
     * 用履约接口会把不相关的状态机失败混进权限断言里。
     */
    private void markCompleted(Long orderId) {
        db.update("UPDATE service_order SET status = 'COMPLETED' WHERE id = ?", orderId);
    }
}
