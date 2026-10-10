package org.imnu.smartcommunitycare.catalog;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 验证列举的四个地址不能修改时段时间；该点状探测不证明不存在其他路由。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SlotTimeImmutableTest extends BaseIntegrationTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate db;

    private String admin;
    private String elder;
    private Long serviceId;
    private Long slotId;

    /** 建单前的时段起止，用于后续比对 */
    private LocalDateTime startBefore;
    private LocalDateTime endBefore;

    @BeforeEach
    void prepare() throws Exception {
        createAccount("it_slotimm_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        admin = loginAndGetToken(mockMvc, "it_slotimm_admin", ADMIN_PASSWORD);
        createElder("it_slotimm_elder", ELDER_PASSWORD);
        elder = loginAndGetToken(mockMvc, "it_slotimm_elder", ELDER_PASSWORD);

        serviceId = createFixtureItem(it -> {
            it.setName("时段起止不变性测试服务");
            it.setDescription("用于验证已预约时段不被管理操作改动起止");
            it.setPrice(new java.math.BigDecimal("40.00"));
            it.setPhone("13500000000");
        });

        LocalDateTime start = LocalDateTime.now().plusDays(2).withHour(9).withMinute(0);
        LocalDateTime end = LocalDateTime.now().plusDays(2).withHour(12).withMinute(0);
        mockMvc.perform(post("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("startAt", start.format(FMT))
                        .param("endAt", end.format(FMT))
                        .param("capacity", "3"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        // 基准从库里读，不取上面构造的入参。
        //
        // <p><b>原因（实测踩到）</b>：我构造的时间带纳秒
        // （如 09:00:36.975479600），而 service_slot 的列是 DATETIME，
        // 落库后只剩整秒（09:00:36）。若拿入参当基准，
        // 每次都会报「时段开始时间被改变」，而实际上没有任何操作改过它——
        // 是基准本身不对。用库里的真值当基准才是在比「有没有被改」。
        slotId = jdbcTemplate.queryForObject(
                "SELECT id FROM service_slot WHERE service_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, serviceId);
        startBefore = startAtOf();
        endBefore = endAtOf();
    }

    /**
     * 验证列举的四个地址不能修改时段时间；该点状探测不证明不存在其他路由。
     */
    @Test
    @DisplayName("管理侧没有可修改时段起止的端点（多种路径均被拒）")
    void noEndpointCanChangeSlotTime() throws Exception {
        String body = """
                {"startAt":"%s","endAt":"%s","capacity":3}
                """.formatted(
                LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).format(FMT),
                LocalDateTime.now().plusDays(3).withHour(17).withMinute(0).format(FMT));

        // 先真实预约一笔，让「已预约的时段」这个前提成立
        String orderId = bookOne();
        assertThat(orderId).as("探测前应先有一笔真实预约").isNotBlank();
        assertThat(occupied()).as("时段应已占用 1 个名额").isEqualTo(1);

        // 直接 PUT 时段本身
        assertRejectedAs("PUT /services/slots/{id}",
                put("/api/services/slots/{id}", slotId)
                        .header("scc-test-token", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));

        // PUT 服务下的时段（另一种可能的挂载位置）
        assertRejectedAs("PUT /services/{sid}/slots/{id}",
                put("/api/services/{sid}/slots/{id}", serviceId, slotId)
                        .header("scc-test-token", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));

        // POST 语义上的「更新时段」
        assertRejectedAs("POST /services/slots/{id}/update",
                post("/api/services/slots/{id}/update", slotId)
                        .header("scc-test-token", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));

        // 起止时间作为查询参数试着改（防止有人用参数形式绕过）
        assertRejectedAs("PUT /services/slots/{id}?startAt&endAt",
                put("/api/services/slots/{id}", slotId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("startAt", LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).format(FMT))
                        .param("endAt", LocalDateTime.now().plusDays(3).withHour(17).withMinute(0).format(FMT)));

        // 关键：起止必须原封不动，且预约与占用不受影响
        assertSlotTimeUnchanged("多次尝试修改起止后");
        assertThat(occupied())
                .as("被拒绝的修改不得影响已占用名额").isEqualTo(1);
    }

    /**
     * 调整容量<b>不动起止</b>，且容量本身生效。
     *
     * <p>{@code updateCapacity} 会更新同一行，若实现里误写了 start/end 字段
     * （例如复用了某个全实体更新），起止就会被改掉。这个用例把那条路径钉死。
     */
    @Test
    @DisplayName("调整容量后时段起止时间不变，且容量按新值生效")
    void capacityChangeDoesNotAlterSlotTime() throws Exception {
        // 先真实占用 1 个名额，让时段处于「已预约」状态
        String orderId = bookOne();
        assertThat(orderId).isNotBlank();
        assertThat(occupied()).isEqualTo(1);

        mockMvc.perform(put("/api/services/slots/{id}/capacity", slotId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("capacity", "5"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        assertThat(capacityOf())
                .as("容量应按新值生效").isEqualTo(5);
        assertSlotTimeUnchanged("调整容量后");
        assertThat(occupied())
                .as("调整容量不应释放已占用名额").isEqualTo(1);
    }

    /**
     * 关闭时段<b>不动起止</b>，且已预约订单保持合法。
     *
     * <p>对应工单第 148 行「关闭阻止新预约但不取消历史订单」。
     * 关闭的语义就是「不再受理」——若它还改了时间，已约好的老人就会被改期，
     * 那是要靠页面和数据库对着核才能发现的严重问题。
     */
    @Test
    @DisplayName("关闭时段后起止时间不变，已预约订单状态与快照均不受影响")
    void closeSlotDoesNotAlterTimeOrExistingOrder() throws Exception {
        String orderId = bookOne();
        assertThat(orderId).isNotBlank();
        assertThat(occupied()).isEqualTo(1);

        // 记录订单的关键快照，关闭后逐项比对
        String statusBefore = orderField(orderId, "status");
        String startSnapBefore = orderField(orderId, "slot_start_snapshot");
        String endSnapBefore = orderField(orderId, "slot_end_snapshot");
        int versionBefore = orderVersion(orderId);
        int createLogsBefore = logCount(orderId, "CREATE");

        mockMvc.perform(put("/api/services/slots/{id}/close", slotId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        // 1) 时段起止不变
        assertSlotTimeUnchanged("关闭时段后");

        // 2) 历史订单不被取消、不被改期
        assertThat(orderField(orderId, "status"))
                .as("关闭时段不得改变已有订单状态").isEqualTo(statusBefore);
        assertThat(orderField(orderId, "slot_start_snapshot"))
                .as("已有订单的开始时间快照不得被覆盖").isEqualTo(startSnapBefore);
        assertThat(orderField(orderId, "slot_end_snapshot"))
                .as("已有订单的结束时间快照不得被覆盖").isEqualTo(endSnapBefore);
        assertThat(orderVersion(orderId))
                .as("关闭时段不得推进订单版本").isEqualTo(versionBefore);
        assertThat(logCount(orderId, "CANCEL"))
                .as("关闭时段不得产生取消日志").isZero();
        assertThat(logCount(orderId, "CREATE"))
                .as("关闭时段不得改写创建日志").isEqualTo(createLogsBefore);

        // 3) 名额不被释放——释放了等于超发
        assertThat(occupied())
                .as("关闭时段不得释放已占用名额").isEqualTo(1);
    }

    /**
     * 下架服务<b>不动时段起止</b>，也不取消该服务下的已有订单。
     *
     * <p>与「关闭」分开写：两者落到的表不同（下架写 {@code service_item}，
     * 关闭写 {@code service_slot}），不能用一条用例代替两条。
     */
    @Test
    @DisplayName("下架服务后时段起止不变，该服务下的已有订单不受影响")
    void disableServiceDoesNotAlterSlotTimeOrOrders() throws Exception {
        String orderId = bookOne();
        assertThat(orderId).isNotBlank();

        String statusBefore = orderField(orderId, "status");
        String startSnapBefore = orderField(orderId, "slot_start_snapshot");
        int createLogsBefore = logCount(orderId, "CREATE");

        mockMvc.perform(put("/api/services/{id}/disable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());

        assertSlotTimeUnchanged("下架服务后");

        assertThat(orderField(orderId, "status"))
                .as("下架服务不得取消已有订单").isEqualTo(statusBefore);
        assertThat(orderField(orderId, "slot_start_snapshot"))
                .as("下架服务不得覆盖订单的时段快照").isEqualTo(startSnapBefore);
        assertThat(logCount(orderId, "CANCEL"))
                .as("下架服务不得产生取消日志").isZero();
        assertThat(logCount(orderId, "CREATE"))
                .as("下架服务不得改写创建日志").isEqualTo(createLogsBefore);
        assertThat(occupied())
                .as("下架服务不得释放已占用名额").isEqualTo(1);
    }

    // ==================== 辅助 ====================

    /**
     * 不存在的测试地址必须返回404或405，500不能作为正确拒绝。
     */
    private void assertRejectedAs(String what,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        int code = mockMvc.perform(req).andReturn().getResponse().getStatus();
        boolean correctlyRejected = code == 404 || code == 405;
        assertThat(correctlyRejected)
                .as("%s 应当被明确拒绝（404 路由不存在 / 405 方法不允许），实际状态码：%d。"
                        + "%s", what, code,
                        code >= 500 ? "这是服务端错误，说明实现有问题，不能算「正确拒绝」"
                                   : "非 404/405 的响应说明路由存在但行为异常，需单独确认")
                .isTrue();
    }

    /** 断言时段的起止时间与建单前完全一致 */
    private void assertSlotTimeUnchanged(String when) {
        assertThat(startAtOf())
                .as("%s：时段开始时间不应改变", when).isEqualTo(startBefore);
        assertThat(endAtOf())
                .as("%s：时段结束时间不应改变", when).isEqualTo(endBefore);
    }

    private LocalDateTime startAtOf() {
        return jdbcTemplate.queryForObject(
                "SELECT start_at FROM service_slot WHERE id = ?", LocalDateTime.class, slotId);
    }

    private LocalDateTime endAtOf() {
        return jdbcTemplate.queryForObject(
                "SELECT end_at FROM service_slot WHERE id = ?", LocalDateTime.class, slotId);
    }

    private int occupied() {
        return jdbcTemplate.queryForObject(
                "SELECT occupied_count FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private int capacityOf() {
        return jdbcTemplate.queryForObject(
                "SELECT capacity FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private String orderField(String orderId, String column) {
        // column 来自本类的固定字面量，不接受外部输入
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM service_order WHERE id = ?", String.class, orderId);
    }

    private int orderVersion(String orderId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT version FROM service_order WHERE id = ?", Integer.class, orderId);
        return v == null ? -1 : v;
    }

    private int logCount(String orderId, String action) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_order_log WHERE order_id = ? AND action = ?",
                Integer.class, orderId, action);
        return n == null ? 0 : n;
    }

    /** 老人真实建一单，返回订单 ID */
    private String bookOne() throws Exception {
        String requestId = java.util.UUID.randomUUID().toString();
        String resp = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/service-orders")
                                .header("scc-test-token", "Bearer " + elder)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"slotId":%d,"contactName":"张建国",
                                         "contactPhone":"13900000003",
                                         "address":"起止不变测试地址","requestId":"%s"}
                                        """.formatted(slotId, requestId)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
        if (!m.find()) {
            throw new IllegalStateException("建单响应中未找到订单 ID：" + resp);
        }
        return m.group(1);
    }
}
