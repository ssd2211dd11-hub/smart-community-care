package org.imnu.smartcommunitycare.user;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证本人资料维护、角色与归属保护、订单快照保持及未登录拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MyProfileUpdateTest extends BaseIntegrationTest {

    private static final String ADMIN = "it_prof_admin";
    private static final String ELDER = "it_prof_elder";
    private static final String STAFF = "it_prof_staff";

    @Autowired
    private MockMvc mockMvc;

    private String adminToken;
    private String elderToken;
    private String staffToken;

    @BeforeEach
    void prepare() throws Exception {
        createAccount(ADMIN, ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        createElder(ELDER, ELDER_PASSWORD);
        createAccount(STAFF, STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);

        adminToken = loginAndGetToken(mockMvc, ADMIN, ADMIN_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);
        staffToken = loginAndGetToken(mockMvc, STAFF, STAFF_PASSWORD);
    }

    @Test
    @DisplayName("A-1 三角色都能改自己的姓名与电话，重新读取一致")
    void allRolesCanUpdateOwnNameAndPhone() throws Exception {
        // 三种角色各改一次，用不同的值，避免互相干扰
        updateProfile(adminToken, """
                {"realName":"管理员新名","phone":"13900001111"}""", 200);
        updateProfile(staffToken, """
                {"realName":"员工新名","phone":"13900002222"}""", 200);
        updateProfile(elderToken, """
                {"realName":"老人新名","phone":"13900003333"}""", 200);

        // 重新读取（不信任本地回显）
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realName").value("管理员新名"))
                .andExpect(jsonPath("$.data.phone").value("13900001111"));

        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realName").value("员工新名"))
                .andExpect(jsonPath("$.data.phone").value("13900002222"));

        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + elderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realName").value("老人新名"))
                .andExpect(jsonPath("$.data.phone").value("13900003333"));
    }

    @Test
    @DisplayName("A-2 请求体硬塞 role/enabled 不改变权限（DTO 层面已排除）")
    void cannotEscalatePrivilegeThroughProfileUpdate() throws Exception {
        // 员工试图把自己改成管理员、并试图启用/改名 username
        String body = """
                {"realName":"越权尝试","phone":"13900004444",
                 "role":"COMMUNITY_ADMIN","enabled":true,
                 "username":"it_hacked","id":1,"userId":1}
                """;
        updateProfile(staffToken, body, 200);

        // 角色没变
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("SERVICE_STAFF"))
                .andExpect(jsonPath("$.data.username").value(STAFF))
                // 姓名与电话确实改了，说明请求被处理了——不是整体被拒
                .andExpect(jsonPath("$.data.realName").value("越权尝试"));
    }

    @Test
    @DisplayName("A-3 老人档案字段随本人资料一起保存并可读回")
    void elderProfileFieldsRoundTrip() throws Exception {
        updateProfile(elderToken, """
                {"birthDate":"1948-03-05","gender":"female",
                 "address":"幸福社区3号楼101","emergencyContact":"李小雨",
                 "emergencyPhone":"13700005555"}""", 200);

        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + elderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.birthDate").value("1948-03-05"))
                .andExpect(jsonPath("$.data.gender").value("female"))
                .andExpect(jsonPath("$.data.address").value("幸福社区3号楼101"))
                .andExpect(jsonPath("$.data.emergencyContact").value("李小雨"))
                .andExpect(jsonPath("$.data.emergencyPhone").value("13700005555"));
    }

    @Test
    @DisplayName("A-4 非老人角色的档案字段恒为 null，不暴露档案结构")
    void nonElderSeesNoProfileFields() throws Exception {
        updateProfile(staffToken, """
                {"birthDate":"1948-03-05","gender":"male","address":"不该出现"}""", 200);

        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.birthDate").doesNotExist())
                .andExpect(jsonPath("$.data.address").doesNotExist());
    }

    @Test
    @DisplayName("A-5 改本人电话不改已有订单的联系人与地址快照")
    void changingOwnPhoneDoesNotAlterExistingOrderSnapshot() throws Exception {
        // 先用老人身份下一单，快照就此固定
        Long serviceId = createFixtureItem(it -> {
            it.setName("资料快照测试服务");
            it.setDescription("用于验证改本人资料不影响订单快照");
            it.setPrice(new java.math.BigDecimal("66.00"));
            it.setPhone("13600000000");
        });
        long slotId = createSlotFor(serviceId, 2);
        String orderId = bookOne(elderToken, slotId, "下单时的联系人", "13811112222", "下单时的地址");

        // 记录订单快照
        String contactBefore = orderSnapshot(orderId, "contact_name");
        String phoneBefore = orderSnapshot(orderId, "contact_phone");
        String addressBefore = orderSnapshot(orderId, "address_snapshot");

        // 老人改自己的电话
        updateProfile(elderToken, """
                {"phone":"13999998888","address":"个人中心改的新住址"}""", 200);

        // 订单快照逐项不变
        assertThat(orderSnapshot(orderId, "contact_name")).isEqualTo(contactBefore);
        assertThat(orderSnapshot(orderId, "contact_phone")).isEqualTo(phoneBefore);
        assertThat(orderSnapshot(orderId, "address_snapshot")).isEqualTo(addressBefore);
        // 确认上面取到的确实是有内容的快照，避免「都是 null 所以相等」
        assertThat(phoneBefore).isEqualTo("13811112222");
        assertThat(addressBefore).isEqualTo("下单时的地址");
    }

    @Test
    @DisplayName("A-8 另一账号无法读取或修改他人资料（接口路径不含 userId）")
    void cannotTouchAnotherAccountsProfile() throws Exception {
        // 前置：员工先改自己的资料，证明接口确实在工作
        updateProfile(staffToken, """
                {"realName":"员工自己的名字","phone":"13700007777"}""", 200);
        String staffBefore = currentRealName(staffToken);
        assertThat(staffBefore).as("前置：员工已改好自己的资料").isEqualTo("员工自己的名字");

        // 老人尝试在请求体里塞 id / userId 指向别人
        updateProfile(elderToken, """
                {"realName":"被冒名修改","phone":"13700009999","id":1,"userId":1}""", 200);

        // 员工的资料必须纹丝不动
        assertThat(currentRealName(staffToken))
                .as("他人无法通过请求体里的 id/userId 改掉本人资料")
                .isEqualTo(staffBefore);

        // 老人自己的资料确实改了（改的是自己，不是被塞进去的 id 指向的人）
        assertThat(currentRealName(elderToken))
                .as("本人只能改自己的资料")
                .isEqualTo("被冒名修改");
    }

    @Test
    @DisplayName("A-9 非管理员不能使用按 userId 维护资料的管理端点")
    void nonAdminsCannotUseTargetedManagementProfileEndpoint() throws Exception {
        // V1.3已添加管理员维护入口；本人资料仍走会话接口，不能借管理路径改他人。
        String uid = String.valueOf(userIdOf(staffToken));
        String staffBefore = currentRealName(staffToken);
        assertThat(putWithStatus("/api/users/" + uid + "/profile", elderToken, "{\"realName\":\"越权\"}"))
                .isEqualTo(403);
        assertThat(putWithStatus("/api/users/" + userIdOf(elderToken) + "/profile", staffToken, "{\"realName\":\"越权\"}"))
                .isEqualTo(403);
        assertThat(currentRealName(staffToken)).isEqualTo(staffBefore);
        int[] codes = {
                putWithStatus("/api/user/profile", elderToken, "{\"realName\":\"越权\"}"),
                putWithStatus("/api/auth/users/profile", elderToken, "{\"realName\":\"越权\"}")
        };
        for (int code : codes) {
            assertThat(code)
                    .as("按 userId 改他人资料的路径不应存在（实际 %d）", code)
                    .isIn(404, 405);
        }
    }

    @Test
    @DisplayName("A-6 未登录不能修改本人资料")
    void anonymousCannotUpdateProfile() throws Exception {
        mockMvc.perform(put("/api/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"realName":"未登录尝试"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A-7 姓名置空被拒，资料不因此被清空")
    void blankRealNameRejected() throws Exception {
        updateProfile(staffToken, """
                {"realName":"有效姓名","phone":"13700006666"}""", 200);

        updateProfile(staffToken, """
                {"realName":"   "}""", 400);

        // 失败不应把之前的值清掉
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realName").value("有效姓名"))
                .andExpect(jsonPath("$.data.phone").value("13700006666"));
    }

    // ==================== 辅助 ====================

    /** 读回当前账号的 realName（不信任本地副本，从服务端取） */
    private String currentRealName(String token) throws Exception {
        String resp = mockMvc.perform(get("/api/auth/me")
                        .header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"realName\"\\s*:\\s*\"([^\"]*)\"").matcher(resp);
        assertThat(m.find()).as("响应中应含 realName：%s", resp).isTrue();
        return m.group(1);
    }

    private Long userIdOf(String token) throws Exception {
        String resp = mockMvc.perform(get("/api/auth/me")
                        .header("scc-test-token", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        // /auth/me 返回的是 id（不是 userId）
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
        assertThat(m.find()).as("响应中应含 id：%s", resp).isTrue();
        return Long.valueOf(m.group(1));
    }

    /** 发起 PUT 并只返回状态码（用于探测不存在的路径） */
    private int putWithStatus(String url, String token, String body) throws Exception {
        return mockMvc.perform(put(url)
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    /**
     * 更新本人资料并断言状态码。
     *
     * <p><b>鉴权头用 {@code scc-test-token}</b>：测试 profile 的
     * {@code application-test.yml} 把 {@code sa-token.token-name} 配成了它，
     * 与生产（{@code scc-token}）<b>有意不同</b>。
     * 两个头写混会得到 401，且失败信息里看不出是头的问题——
     * 本类首跑 6 个用例全挂在这一点上。真实前端联调才用 {@code scc-token}。
     */
    private void updateProfile(String token, String body, int expectedStatus) throws Exception {
        int code = mockMvc.perform(put("/api/auth/profile")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
        assertThat(code)
                .as("更新本人资料，请求体=%s", body)
                .isEqualTo(expectedStatus);
    }

    private long createSlotFor(Long serviceId, int capacity) {
        org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot slot =
                new org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot();
        slot.setServiceId(serviceId);
        slot.setStartAt(java.time.LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        slot.setEndAt(java.time.LocalDateTime.now().plusDays(1).withHour(12).withMinute(0));
        slot.setCapacity(capacity);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(java.time.LocalDateTime.now());
        slot.setUpdatedAt(java.time.LocalDateTime.now());
        // 用 JDBC 插入后按 service_id + 起始时间精确取回，
        // 不用 LAST_INSERT_ID()（连接池下不保证同一条连接）
        jdbcTemplate.update(
                "INSERT INTO service_slot (service_id, start_at, end_at, capacity,"
                        + " occupied_count, enabled, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, 0, 1, NOW(3), NOW(3))",
                serviceId,
                java.sql.Timestamp.valueOf(slot.getStartAt()),
                java.sql.Timestamp.valueOf(slot.getEndAt()),
                capacity);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM service_slot WHERE service_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, serviceId);
        if (id == null) {
            throw new IllegalStateException("时段插入后未取到 ID");
        }
        return id;
    }

    private String bookOne(String token, long slotId, String contactName,
                           String contactPhone, String address) throws Exception {
        String requestId = java.util.UUID.randomUUID().toString();
        int code = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"%s","contactPhone":"%s",
                                 "address":"%s","requestId":"%s"}
                                """.formatted(slotId, contactName, contactPhone, address, requestId)))
                .andReturn().getResponse().getStatus();
        assertThat(code).as("建单应成功").isEqualTo(200);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM service_order WHERE request_id = ?", String.class, requestId);
    }

    /** 列名只来自本类的固定字面量，不接受外部输入 */
    private String orderSnapshot(String orderId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM service_order WHERE id = ?", String.class, orderId);
    }
}
