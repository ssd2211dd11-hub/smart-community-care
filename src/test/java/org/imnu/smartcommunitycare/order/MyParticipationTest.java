package org.imnu.smartcommunitycare.order;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证转派后保留本人参与记录，摘要不含当前电话或地址，非人员访问被拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MyParticipationTest extends BaseIntegrationTest {
    @Test
    void pagedParticipationPreservesTransferPrivacyAndExactTotal() throws Exception {
        String order=bookOrder();assign(order,staffAId);accept(order,staffAToken);transfer(order,staffBId);
        String body=mockMvc.perform(get("/api/service-orders/my-participations/paged")
                .header("scc-test-token","Bearer "+staffAToken).param("pageSize","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records.length()").value(1)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains(order).doesNotContain("contactPhone","addressSnapshot","13900007777","参与历史测试地址");
        mockMvc.perform(get("/api/service-orders/{id}/view",order).header("scc-test-token","Bearer "+staffAToken)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/service-orders/my-participations/paged").header("scc-test-token","Bearer "+staffAToken).param("pageNum","2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.records.length()").value(0));
    }

    private static final String ELDER = "it_part_elder";
    private static final String ADMIN = "it_part_admin";
    private static final String STAFF_A = "it_part_staffA";
    private static final String STAFF_B = "it_part_staffB";

    @Autowired
    private MockMvc mockMvc;

    private String elderToken;
    private String adminToken;
    private String staffAToken;
    private String staffBToken;
    private Long staffAId;
    private Long staffBId;
    private Long serviceId;
    private Long slotId;

    @BeforeEach
    void prepare() throws Exception {
        createElder(ELDER, ELDER_PASSWORD);
        createAccount(ADMIN, ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        staffAId = createAccount(STAFF_A, STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        staffBId = createAccount(STAFF_B, STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);

        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);
        adminToken = loginAndGetToken(mockMvc, ADMIN, ADMIN_PASSWORD);
        staffAToken = loginAndGetToken(mockMvc, STAFF_A, STAFF_PASSWORD);
        staffBToken = loginAndGetToken(mockMvc, STAFF_B, STAFF_PASSWORD);

        serviceId = createFixtureItem(it -> {
            it.setName("参与历史测试服务");
            it.setDescription("用于验证转派后仍可查参与历史");
            it.setPrice(new java.math.BigDecimal("72.00"));
            it.setPhone("13400000000");
        });
        slotId = createSlot();
    }

    @Test
    @DisplayName("C-1 转派后旧受派人仍能看到自己参与过的订单")
    void transferredStaffStillSeesOwnParticipation() throws Exception {
        String orderId = bookOrder();

        // 先分派给 A，A 才能接单（步骤照既有 OrderAssignmentTest 的
        // bookOrder → assign → accept → transfer）
        assign(orderId, staffAId);
        accept(orderId, staffAToken);
        // 管理员转派给 B
        transfer(orderId, staffBId);

        // A 的当前任务里已经没有这单
        String tasksA = mockMvc.perform(get("/api/service-orders/my-tasks")
                        .header("scc-test-token", "Bearer " + staffAToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(tasksA)
                .as("转派后 A 的当前任务里不应再有该订单")
                .doesNotContain(orderId);

        // 但 A 的参与历史里仍在
        String partA = mockMvc.perform(get("/api/service-orders/my-participations")
                        .header("scc-test-token", "Bearer " + staffAToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(partA)
                .as("转派后 A 的参与历史里应仍能看到自己接过的那单")
                .contains(orderId);
    }

    @Test
    @DisplayName("C-2 参与历史不含当前电话与地址（后端就没给，不是页面遮）")
    void participationSummaryIsMasked() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);
        accept(orderId, staffAToken);

        String resp = mockMvc.perform(get("/api/service-orders/my-participations")
                        .header("scc-test-token", "Bearer " + staffAToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 下单时用的电话与地址
        assertThat(resp)
                .as("参与历史不得返回当前联系人电话")
                .doesNotContain("13900007777");
        assertThat(resp)
                .as("参与历史不得返回当前服务地址")
                .doesNotContain("参与历史测试地址");

        // 但要保证「不是因为整体返回空才没这些字段」
        assertThat(resp)
                .as("参与历史应有实际内容（订单号与服务名快照）")
                .contains(orderId);
    }

    @Test
    @DisplayName("C-3 参与历史只含本人动作，不含别人的")
    void participationOnlyContainsOwnActions() throws Exception {
        String orderA = bookOrder();
        assign(orderA, staffAId);
        accept(orderA, staffAToken);

        String orderB = bookOrder();
        assign(orderB, staffBId);
        accept(orderB, staffBToken);

        String partA = mockMvc.perform(get("/api/service-orders/my-participations")
                        .header("scc-test-token", "Bearer " + staffAToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(partA).as("A 的历史应含自己接的").contains(orderA);
        assertThat(partA).as("A 的历史不应含 B 接的").doesNotContain(orderB);
    }

    @Test
    @DisplayName("C-4 非工作人员访问参与历史被拒")
    void nonStaffCannotAccessParticipation() throws Exception {
        // 老人
        mockMvc.perform(get("/api/service-orders/my-participations")
                        .header("scc-test-token", "Bearer " + elderToken))
                .andExpect(status().isForbidden());
        // 管理员
        mockMvc.perform(get("/api/service-orders/my-participations")
                        .header("scc-test-token", "Bearer " + adminToken))
                .andExpect(status().isForbidden());
        // 未登录
        mockMvc.perform(get("/api/service-orders/my-participations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("C-5 参与历史只统计本人动作：转派后由 B 接手，A 仍只有自己那一段")
    void participationTracksOwnActionsOnly() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);
        accept(orderId, staffAToken);
        // 转派给 B，B 接手
        transfer(orderId, staffBId);

        String partB = mockMvc.perform(get("/api/service-orders/my-participations")
                        .header("scc-test-token", "Bearer " + staffBToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // B 只是接手、没执行动作，因此此刻还没有参与记录
        assertThat(partB)
                .as("B 仅接手未执行动作，不应有参与记录")
                .doesNotContain(orderId);

        String partA = mockMvc.perform(get("/api/service-orders/my-participations")
                        .header("scc-test-token", "Bearer " + staffAToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A 接过单，即使已被转出，参与记录仍在——这是「参与历史」与
        // 「当前任务」的核心差别
        assertThat(partA)
                .as("A 接过单，转出后参与记录仍应保留")
                .contains(orderId);
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
                                {"slotId":%d,"contactName":"参与历史联系人",
                                 "contactPhone":"13900007777",
                                 "address":"参与历史测试地址","requestId":"%s"}
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
                                {"staffId":%d,"reason":"参与历史测试分派"}
                                """.formatted(staffId))))
                .andExpect(status().isOk());
    }

    private void accept(String orderId, String token) throws Exception {
        mockMvc.perform(post("/api/service-orders/" + orderId + "/accept")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withOrderVersion(orderId, """
                                {"reason":"我去接单"}""")))
                .andExpect(status().isOk());
    }

    private void transfer(String orderId, Long newStaffId) throws Exception {
        mockMvc.perform(post("/api/service-orders/" + orderId + "/transfer")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withOrderVersion(orderId, """
                                {"newStaffId":%d,"reason":"转派给他人"}
                                """.formatted(newStaffId))))
                .andExpect(status().isOk());
    }
}
