package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证正确版本成功、过期版本409及缺失版本400。
 * 失败不得改变订单、日志或名额，冲突说明应包含客户端和当前版本。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderActionVersionTest extends BaseIntegrationTest {

    private static final String ELDER = "it_ver_elder";
    private static final String ADMIN = "it_ver_admin";
    private static final String STAFF_A = "it_ver_staffA";
    private static final String STAFF_B = "it_ver_staffB";

    @Autowired
    private MockMvc mockMvc;

    private String elderToken;
    private String adminToken;
    private String staffAToken;
    private Long staffAId;
    private Long staffBId;
    private Long serviceId;
    private Long slotId;

    @BeforeEach
    void prepare() throws Exception {
        createElder(ELDER, ELDER_PASSWORD);
        createAccount(ADMIN, ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        staffAId = createAccount(STAFF_A, STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount(STAFF_B, STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);

        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);
        adminToken = loginAndGetToken(mockMvc, ADMIN, ADMIN_PASSWORD);
        staffAToken = loginAndGetToken(mockMvc, STAFF_A, STAFF_PASSWORD);

        serviceId = createFixtureItem(it -> {
            it.setName("版本契约测试服务");
            it.setDescription("用于验证动作携带 version");
            it.setPrice(new java.math.BigDecimal("88.00"));
            it.setPhone("13300000000");
        });
        slotId = createSlot();
    }

    @Test
    @DisplayName("V-1 带正确版本的动作正常成功")
    void correctVersionSucceeds() throws Exception {
        String orderId = bookOrder();
        int version = currentVersionOf(orderId);

        mockMvc.perform(post("/api/service-orders/" + orderId + "/assign")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"staffId":%d,"reason":"带版本分派","version":%d}
                                """.formatted(staffAId, version)))
                .andExpect(status().isOk());

        assertThat(statusOf(orderId)).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("V-2 带过期版本的动作返回 409，且状态与名额均未改变")
    void staleVersionRejectedWithoutSideEffect() throws Exception {
        String orderId = bookOrder();
        int stale = currentVersionOf(orderId);

        // 分派让订单进入 ASSIGNED（这是版本推进）
        assign(orderId, staffAId);
        int occupiedBefore = occupiedOf();
        String statusBefore = statusOf(orderId);
        assertThat(currentVersionOf(orderId))
                .as("前置：分派已推进版本，%d 才是过期版本", stale)
                .isGreaterThan(stale);

        // 用过期版本接单。选接单而非拒接，是因为：
        // 状态机校验在 VersionGuard **之前**，动作必须先处于「状态允许」，
        // 否则会先被状态机拒掉（400），VersionGuard 根本没机会执行。
        var resp = mockMvc.perform(post("/api/service-orders/" + orderId + "/accept")
                        .header("scc-test-token", "Bearer " + staffAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"用过期版本","version":%d}
                                """.formatted(stale)))
                .andReturn().getResponse();
        int code = resp.getStatus();
        String body = resp.getContentAsString();

        assertThat(code).as("过期版本必须被拒").isEqualTo(409);
        // 关键：提示里必须带版本号，才能确定是 VersionGuard 拦下的。
        // 否则 409 可能来自 acceptByStaff 的 WHERE version 条件（updated != 1），
        // 那是另一条防线——测不出 VersionGuard 是否真的被消费。
        // 用「你的版本 N，当前 M」这个固定措辞做锚点，
        // 不能只 contains(String.valueOf(stale))——stale 为 0 时，
        // JSON 里的时间戳就含 "0"，断言会假通过。
        assertThat(body)
                .as("冲突提示应含「你的版本 %d」，说明是 VersionGuard 生效而非 SQL 的 updated!=1", stale)
                .contains("你的版本 " + stale + "，当前");
        // 关键：被拒的动作不得留下任何痕迹
        assertThat(statusOf(orderId)).as("状态不应改变").isEqualTo(statusBefore);
        assertThat(occupiedOf()).as("名额不应被释放或占用").isEqualTo(occupiedBefore);
    }

    @Test
    @DisplayName("V-3 冲突提示同时给出客户端版本与当前版本")
    void conflictMessageCarriesBothVersions() throws Exception {
        String orderId = bookOrder();
        int stale = currentVersionOf(orderId);
        assign(orderId, staffAId);
        int now = currentVersionOf(orderId);

        String body = mockMvc.perform(post("/api/service-orders/" + orderId + "/accept")
                        .header("scc-test-token", "Bearer " + staffAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"冲突提示校验","version":%d}
                                """.formatted(stale)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(String.valueOf(stale))))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString(String.valueOf(now))))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("提示应说明请刷新").contains("刷新");
    }

    @Test
    @DisplayName("V-4 缺 version 返回 400，不执行动作")
    void absentVersionIsRejected() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);

        // 故意不带版本，不能借正常请求辅助补上。
        mockMvc.perform(post("/api/service-orders/" + orderId + "/accept")
                        .header("scc-test-token", "Bearer " + staffAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"不带版本的接单"}"""))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(orderId)).isEqualTo("ASSIGNED");
    }

    /**
     * V-6「开工」也受版本保护。
     *
     * <p>本轮之前 {@code start} 不收请求体，页面无处携带版本，
     * 后端固定用「服务端刚读出的当前版本」——那等于没有并发检查：
     * 两个人同时点开工，第二个读到的已是最新版本，WHERE 里的 version
     * 永远等于它自己刚读的，于是照样成功。
     *
     * <p>本用例证明接上通道后行为正确：带正确版本成功，带过期版本 409
     * 且状态、版本、日志都不动。
     */
    @Test
    @DisplayName("V-6 开工动作受版本保护：过期版本 409 且状态与日志不变")
    void startActionIsVersionChecked() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);
        accept(orderId, staffAToken);
        // 开工校验的是订单的 slot_start_snapshot（下单时的快照），
        // 不是时段当前值——所以必须改订单快照，改 service_slot 无效。
        jdbcTemplate.update("UPDATE service_order SET slot_start_snapshot = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(1)),
                orderId);

        int stale = currentVersionOf(orderId);
        // 先让版本前进，制造「页面手上的版本已过时」
        mockMvc.perform(post("/api/service-orders/" + orderId + "/start")
                        .header("scc-test-token", "Bearer " + staffAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":%d}""".formatted(currentVersionOf(orderId))))
                .andExpect(status().isOk());

        // 现在用旧版本再开工：应被拒
        int logsBefore = logCount(orderId, "START");
        mockMvc.perform(post("/api/service-orders/" + orderId + "/start")
                        .header("scc-test-token", "Bearer " + staffAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":%d}""".formatted(stale)))
                .andExpect(status().isConflict());

        assertThat(statusOf(orderId)).isEqualTo("IN_SERVICE");
        assertThat(logCount(orderId, "START"))
                .as("被拒的开工不得新增日志").isEqualTo(logsBefore);
    }

    /**
     * V-7「确认完成」同样受版本保护。
     *
     * <p>与 {@link #startActionIsVersionChecked()} 同因：确认原先也不收请求体。
     * 老人在两个页面上同时点确认，是最容易发生的并发场景。
     */
    @Test
    @DisplayName("V-7 老人确认受版本保护：过期版本 409 且状态与日志不变")
    void confirmActionIsVersionChecked() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);
        accept(orderId, staffAToken);
        // 同 V-6：开工看的是订单快照
        jdbcTemplate.update("UPDATE service_order SET slot_start_snapshot = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(1)),
                orderId);
        mockMvc.perform(post("/api/service-orders/" + orderId + "/start")
                        .header("scc-test-token", "Bearer " + staffAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":%d}""".formatted(currentVersionOf(orderId))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/service-orders/" + orderId + "/submit-result")
                        .header("scc-test-token", "Bearer " + staffAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"result":"服务已完成","version":%d}"""
                                .formatted(currentVersionOf(orderId))))
                .andExpect(status().isOk());

        int stale = currentVersionOf(orderId);
        // 制造一次版本推进
        jdbcTemplate.update("UPDATE service_order SET version = version + 1 WHERE id = ?", orderId);

        int logsBefore = logCount(orderId, "CONFIRM");
        mockMvc.perform(post("/api/service-orders/" + orderId + "/confirm")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":%d}""".formatted(stale)))
                .andExpect(status().isConflict());

        assertThat(statusOf(orderId)).as("被拒的确认不得改变状态").isEqualTo("PENDING_CONFIRMATION");
        assertThat(logCount(orderId, "CONFIRM"))
                .as("被拒的确认不得新增日志").isEqualTo(logsBefore);
    }

    @Test
    @DisplayName("V-5 服务人员的动作同样受版本保护（非管理员路径也生效）")
    void staffActionsAlsoVersionChecked() throws Exception {
        String orderId = bookOrder();
        int stale = currentVersionOf(orderId);
        assign(orderId, staffAId);

        // 用过期版本接单（员工动作同样受保护）
        var resp = mockMvc.perform(post("/api/service-orders/" + orderId + "/accept")
                        .header("scc-test-token", "Bearer " + staffAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"用过期版本接单","version":%d}
                                """.formatted(stale)))
                .andReturn().getResponse();

        assertThat(resp.getStatus()).as("过期版本的接单必须被拒").isEqualTo(409);
        assertThat(resp.getContentAsString())
                .as("提示含「你的版本 %d」，说明确实是 VersionGuard 拦下", stale)
                .contains("你的版本 " + stale + "，当前");
        assertThat(statusOf(orderId)).as("状态不应改变").isEqualTo("ASSIGNED");
    }

    // ==================== 辅助 ====================

    private Long createSlot() {
        var slot = new org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot();
        slot.setServiceId(serviceId);
        slot.setStartAt(java.time.LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        slot.setEndAt(java.time.LocalDateTime.now().plusDays(1).withHour(12).withMinute(0));
        slot.setCapacity(5);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(java.time.LocalDateTime.now());
        slot.setUpdatedAt(java.time.LocalDateTime.now());
        jdbcTemplate.update(
                "INSERT INTO service_slot (service_id, start_at, end_at, capacity,"
                        + " occupied_count, enabled, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 5, 0, 1, NOW(3), NOW(3))",
                serviceId,
                java.sql.Timestamp.valueOf(slot.getStartAt()),
                java.sql.Timestamp.valueOf(slot.getEndAt()));
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM service_slot WHERE service_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, serviceId);
        if (id == null) {
            throw new IllegalStateException("时段插入后未取到 ID");
        }
        return id;
    }

    private String bookOrder() throws Exception {
        String requestId = java.util.UUID.randomUUID().toString();
        String resp = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"版本测试联系人",
                                 "contactPhone":"13800001234",
                                 "address":"版本测试地址","requestId":"%s"}
                                """.formatted(slotId, requestId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
        if (!m.find()) {
            throw new IllegalStateException("建单响应中未找到订单 ID：" + resp);
        }
        return m.group(1);
    }

    private void assign(String orderId, Long staffId) throws Exception {
        mockMvc.perform(post("/api/service-orders/" + orderId + "/assign")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withOrderVersion(orderId, """
                                {"staffId":%d,"reason":"版本测试分派"}
                                """.formatted(staffId))))
                .andExpect(status().isOk());
    }

    private void accept(String orderId, String token) throws Exception {
        mockMvc.perform(post("/api/service-orders/" + orderId + "/accept")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withOrderVersion(orderId, """
                                {"reason":"版本测试接单"}""")))
                .andExpect(status().isOk());
    }

    private int logCount(String orderId, String action) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_order_log WHERE order_id = ? AND action = ?",
                Integer.class, orderId, action);
        return n == null ? 0 : n;
    }

    private int currentVersionOf(String orderId) {
        Integer v = jdbcTemplate.queryForObject(
                "SELECT version FROM service_order WHERE id = ?", Integer.class, orderId);
        return v == null ? -1 : v;
    }

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM service_order WHERE id = ?", String.class, orderId);
    }

    private int occupiedOf() {
        return jdbcTemplate.queryForObject(
                "SELECT occupied_count FROM service_slot WHERE id = ?", Integer.class, slotId);
    }
}
