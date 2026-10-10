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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证三角色对有权订单的详情访问、当前责任人姓名及未分派显示。
 * 无权访问必须由后端拒绝，且响应不泄漏敏感字段。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderDetailViewTest extends BaseIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String adminToken;
    private String elderToken;
    private String otherElderToken;
    private String staffToken;
    private String otherStaffToken;
    private Long staffUserId;
    private Long serviceId;
    private Long slotId;

    @BeforeEach
    void prepare() throws Exception {
        createAccount("it_det_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        adminToken = loginAndGetToken(mockMvc, "it_det_admin", ADMIN_PASSWORD);

        createElder("it_det_elder", ELDER_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, "it_det_elder", ELDER_PASSWORD);
        createElder("it_det_other", ELDER_PASSWORD);
        otherElderToken = loginAndGetToken(mockMvc, "it_det_other", ELDER_PASSWORD);

        createAccount("it_det_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        staffToken = loginAndGetToken(mockMvc, "it_det_staff", STAFF_PASSWORD);
        createAccount("it_det_staff2", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        otherStaffToken = loginAndGetToken(mockMvc, "it_det_staff2", STAFF_PASSWORD);

        staffUserId = userIdOf("it_det_staff");

        serviceId = createFixtureItem(it -> {
            it.setName("公共详情测试服务");
            it.setDescription("用于验证三角色公共详情");
            it.setPrice(new BigDecimal("55.00"));
            it.setPhone("13100000000");
        });
        slotId = createSlot(10);
    }

    // ==================== 一、三角色都能进详情 ====================

    @Test
    @DisplayName("三角色都能打开同一订单的详情，且看到同一订单号")
    void allThreeRolesCanOpenDetail() throws Exception {
        Long orderId = bookAs(elderToken);

        // 先通过正常API分派给登记人员，再验证本人详情。
        mockMvc.perform(post("/api/service-orders/{id}/assign", orderId)
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"staffId":%d,"reason":"详情测试分派"}
                                """.formatted(staffUserId)))
                .andExpect(status().isOk());

        JsonNode admin = view(adminToken, orderId);
        JsonNode elder = view(elderToken, orderId);
        JsonNode staff = view(staffToken, orderId);

        assertThat(admin.path("orderNo").asText()).isNotBlank();
        assertThat(elder.path("orderNo").asText())
                .as("老人与管理员应看到同一订单").isEqualTo(admin.path("orderNo").asText());
        assertThat(staff.path("orderNo").asText())
                .as("分派后工作人员应看到同一订单").isEqualTo(admin.path("orderNo").asText());
    }

    @Test
    @DisplayName("详情含服务/价格/时段/联系方式快照与状态、时间线所需字段")
    void detailCarriesSnapshotsAndStatus() throws Exception {
        Long orderId = bookAs(elderToken);
        JsonNode detail = view(elderToken, orderId);

        assertThat(detail.path("id").asText()).as("ID 应为字符串").isNotEmpty();
        assertThat(detail.path("serviceNameSnapshot").asText())
                .as("应含服务名快照").isEqualTo("公共详情测试服务");
        assertThat(detail.path("priceSnapshot").asDouble())
                .as("应含价格快照").isEqualTo(55.00);
        assertThat(detail.path("slotStartSnapshot").asText()).as("应含预约开始时间").isNotEmpty();
        assertThat(detail.path("slotEndSnapshot").asText()).as("应含预约结束时间").isNotEmpty();
        assertThat(detail.path("contactName").asText()).as("应含联系人").isNotEmpty();
        assertThat(detail.path("contactPhone").asText()).as("应含联系电话").isNotEmpty();
        assertThat(detail.path("status").asText()).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(detail.path("version").isNumber())
                .as("应带 version 供动作提交时回传").isTrue();
    }

    // ==================== 二、责任人 ====================

    @Test
    @DisplayName("未分派时责任人明确为「未分派」，不是空白也不是历史姓名")
    void unassignedShowsExplicitlyNotAssigned() throws Exception {
        Long orderId = bookAs(elderToken);
        JsonNode detail = view(elderToken, orderId);

        // 注意：序列化器**省略 null 字段**（实测响应里没有 staffId / staffName），
        // 所以不能用 isNull() 判断——那会拿到 MissingNode 而失败。
        // 页面据此「没有这两个字段就显示未分派」。
        assertThat(detail.has("staffId"))
                .as("未分派时不应返回 staffId 字段").isFalse();
        assertThat(detail.has("staffName"))
                .as("未分派时不应返回 staffName 字段，页面显示「未分派」").isFalse();
    }

    @Test
    @DisplayName("分派后详情显示当前责任人的姓名")
    void assignedShowsCurrentStaffName() throws Exception {
        Long orderId = bookAs(elderToken);

        mockMvc.perform(post("/api/service-orders/{id}/assign", orderId)
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"staffId":%d,"reason":"详情测试分派"}
                                """.formatted(staffUserId)))
                .andExpect(status().isOk());

        JsonNode detail = view(elderToken, orderId);
        assertThat(detail.path("staffId").asText())
                .as("应显示当前受派人 ID").isEqualTo(String.valueOf(staffUserId));
        assertThat(detail.path("staffName").asText())
                .as("应显示当前受派人姓名，而不是只显示裸 ID").isNotBlank();
    }

    @Test
    @DisplayName("换人后详情显示新责任人，不停留在历史受派人")
    void detailFollowsCurrentStaffAfterTransfer() throws Exception {
        Long orderId = bookAs(elderToken);
        Long otherStaffId = userIdOf("it_det_staff2");

        // 先分派给甲；转派允许 ASSIGNED / ACCEPTED。
        mockMvc.perform(post("/api/service-orders/{id}/assign", orderId)
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"staffId":%d,"reason":"先分给甲"}
                                """.formatted(staffUserId)))
                .andExpect(status().isOk());
        assertThat(view(elderToken, orderId).path("status").asText())
                .as("前置条件：分派后应为已分派").isEqualTo("ASSIGNED");
        String firstName = view(elderToken, orderId).path("staffName").asText();
        assertThat(firstName).as("分派后应有责任人姓名").isNotBlank();

        // 转派用 newStaffId 字段（TransferOrderRequest），不是 staffId。
        // 本例走接单后的转派；待接单直接转派另由 P3ActionContractTest 验证。
        mockMvc.perform(post("/api/service-orders/{id}/accept", orderId)
                        .header("scc-test-token", "Bearer " + staffToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"甲接单","version":1}
                                """))
                .andExpect(status().isOk());
        assertThat(view(elderToken, orderId).path("status").asText())
                .as("前置条件：接单后应为已接单").isEqualTo("ACCEPTED");

        mockMvc.perform(post("/api/service-orders/{id}/transfer", orderId)
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":2,"newStaffId":%d,"reason":"改派给乙"}
                                """.formatted(otherStaffId)))
                .andExpect(status().isOk());

        JsonNode after = view(elderToken, orderId);
        assertThat(after.path("staffId").asText())
                .as("应显示新受派人 ID").isEqualTo(String.valueOf(otherStaffId));
        assertThat(after.path("staffName").asText())
                .as("应显示新受派人姓名")
                .isNotEqualTo(firstName);
    }

    // ==================== 三、越权与敏感字段 ====================

    @Test
    @DisplayName("越权访问详情被拒：他人订单、未分派的工作人员、未登录")
    void detailRejectsUnauthorized() throws Exception {
        Long orderId = bookAs(elderToken);

        // 另一位老人
        mockMvc.perform(get("/api/service-orders/{id}/view", orderId)
                        .header("scc-test-token", "Bearer " + otherElderToken))
                .andExpect(status().isNotFound());

        // 未分派订单对该人员不可见。
        mockMvc.perform(get("/api/service-orders/{id}/view", orderId)
                        .header("scc-test-token", "Bearer " + staffToken))
                .andExpect(status().isNotFound());

        // 分派给甲后，乙仍不可见
        mockMvc.perform(post("/api/service-orders/{id}/assign", orderId)
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"staffId":%d,"reason":"分派给甲"}
                                """.formatted(staffUserId)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/service-orders/{id}/view", orderId)
                        .header("scc-test-token", "Bearer " + otherStaffToken))
                .andExpect(status().isNotFound());

        // 未登录
        mockMvc.perform(get("/api/service-orders/{id}/view", orderId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("详情不返回 requestHash / requestId 等内部字段")
    void detailDoesNotLeakInternalFields() throws Exception {
        Long orderId = bookAs(elderToken);
        String raw = mockMvc.perform(get("/api/service-orders/{id}/view", orderId)
                        .header("scc-test-token", "Bearer " + elderToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = JSON.readTree(raw).path("data");
        // 这些是防重机制的实现细节，不该出现在页面上
        assertThat(data.has("requestHash")).as("不得返回 requestHash").isFalse();
        assertThat(data.has("requestId")).as("不得返回 requestId").isFalse();
        // 也不该把整个用户对象带出来
        assertThat(data.has("passwordHash")).as("不得返回密码哈希").isFalse();
    }

    @Test
    @DisplayName("ID 一律为字符串，不是 JSON number")
    void idsAreStringsNotNumbers() throws Exception {
        Long orderId = bookAs(elderToken);
        mockMvc.perform(post("/api/service-orders/{id}/assign", orderId)
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":0,"staffId":%d,"reason":"ID 契约检查"}
                                """.formatted(staffUserId)))
                .andExpect(status().isOk());

        JsonNode detail = view(elderToken, orderId);
        assertThat(detail.path("id").isString())
                .as("订单 ID 应为字符串，实际类型：%s", detail.path("id").getNodeType())
                .isTrue();
        assertThat(detail.path("staffId").isString())
                .as("责任人 ID 应为字符串，实际类型：%s", detail.path("staffId").getNodeType())
                .isTrue();
    }

    // ==================== 辅助 ====================

    /** 打开详情并返回 data 节点 */
    private JsonNode view(String token, Long orderId) throws Exception {
        String body = mockMvc.perform(get("/api/service-orders/{id}/view", orderId)
                        .header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = JSON.readTree(body).path("data");
        if (!data.isObject()) {
            throw new IllegalStateException("详情响应 data 应为对象：" + body);
        }
        return data;
    }

    private Long userIdOf(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

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

    private Long bookAs(String token) throws Exception {
        String resp = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"详情测试",
                                 "contactPhone":"13900000009",
                                 "address":"详情测试地址","requestId":"%s"}
                                """.formatted(slotId, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
        if (!m.find()) {
            throw new IllegalStateException("建单响应中未找到订单 ID：" + resp);
        }
        return Long.valueOf(m.group(1));
    }
}
