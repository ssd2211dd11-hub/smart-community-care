package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceItemMapper;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 订单分派、接单、拒接与转派。
 *
 * <p>这些动作<b>都不改变名额</b>，所以每个用例都断言名额前后一致——
 * 名额只在创建与首次合法取消时变动。
 */
@AutoConfigureMockMvc
class OrderAssignmentTest extends BaseIntegrationTest {

    private static final String HEADER = "scc-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceItemMapper itemMapper;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String adminToken;
    private String staffAToken;
    private String staffBToken;
    private String elderToken;
    private Long staffAId;
    private Long staffBId;
    private Long slotId;

    @BeforeEach
    void prepare() throws Exception {
        createElder("it_as_elder", ELDER_PASSWORD);
        createAccount("it_as_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        staffAId = createAccount("it_as_staffA", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        staffBId = createAccount("it_as_staffB", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        // 停用的服务人员：不能被新分派
        createAccount("it_as_staffOff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, false);

        adminToken = loginAndGetToken(mockMvc, "it_as_admin", ADMIN_PASSWORD);
        staffAToken = loginAndGetToken(mockMvc, "it_as_staffA", STAFF_PASSWORD);
        staffBToken = loginAndGetToken(mockMvc, "it_as_staffB", STAFF_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, "it_as_elder", ELDER_PASSWORD);

        Long factoryServiceId = createFixtureItem(it -> {
            it.setName("分派测试服务");
            it.setDescription("用于分派流程测试");
            it.setPrice(new BigDecimal("90.00"));
            it.setPhone("13800000000");
        });

        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(factoryServiceId);
        slot.setStartAt(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        slot.setEndAt(LocalDateTime.now().plusDays(1).withHour(12).withMinute(0));
        slot.setCapacity(10);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(slot);
        slotId = slot.getId();
    }

    // ---------------- 分派 ----------------

    @Test
    @DisplayName("管理员分派后状态为待接单、受派人正确，名额不变")
    void assignSetsStaffAndKeepsSlot() throws Exception {
        String orderId = bookOrder();

        mockMvc.perform(act("/assign", orderId, adminToken, """
                {"staffId":%d,"reason":"安排王服务"}
                """.formatted(staffAId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.staffId").value(staffAId.toString()));

        // 名额不受分派影响
        assertThat(occupied()).isEqualTo(1);
        assertThat(staffIdOf(orderId)).isEqualTo(staffAId);
    }

    @Test
    @DisplayName("不能分派给停用人员")
    void cannotAssignToDisabledStaff() throws Exception {
        String orderId = bookOrder();
        Long offId = userId("it_as_staffOff");

        mockMvc.perform(act("/assign", orderId, adminToken, """
                {"staffId":%d,"reason":"试一下"}
                """.formatted(offId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("停用")));
    }

    @Test
    @DisplayName("不能分派给老人或管理员")
    void cannotAssignToNonStaff() throws Exception {
        String orderId = bookOrder();
        Long elderId = userId("it_as_elder");

        mockMvc.perform(act("/assign", orderId, adminToken, """
                {"staffId":%d}
                """.formatted(elderId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("老人不能分派")
    void elderCannotAssign() throws Exception {
        String orderId = bookOrder();

        mockMvc.perform(act("/assign", orderId, elderToken, """
                {"staffId":%d}
                """.formatted(staffAId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("已分派的订单不能再次分派")
    void cannotAssignTwice() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);

        mockMvc.perform(act("/assign", orderId, adminToken, """
                {"staffId":%d}
                """.formatted(staffBId)))
                .andExpect(status().isConflict());
    }

    // ---------------- 接单与拒接 ----------------

    @Test
    @DisplayName("当前受派人可接单，状态为待服务，名额不变")
    void staffCanAccept() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);

        mockMvc.perform(act("/accept", orderId, staffAToken, """
                {"reason":"收到，明天准时上门"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.acceptedAt").isNotEmpty());

        assertThat(occupied()).isEqualTo(1);
    }

    @Test
    @DisplayName("非受派人不能接单")
    void nonAssignedStaffCannotAccept() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);

        // B 没被分派，接单应失败
        mockMvc.perform(act("/accept", orderId, staffBToken, """
                {"reason":"我来吧"}
                """))
                .andExpect(status().isNotFound());

        assertThat(statusOf(orderId)).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("拒接后回到待分派，受派人被真正清空")
    void rejectClearsStaff() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);

        // 状态机只允许 ASSIGNED 拒接（已接单的不能直接拒接），
        // 所以这里不先接单
        mockMvc.perform(act("/reject", orderId, staffAToken, """
                {"reason":"当天已有其他安排"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_ASSIGNMENT"));

        // 关键：必须验证数据库里 staff_id 真的是 NULL，
        // 只看 JSON 不够——MyBatis-Plus 会跳过 null 字段，
        // 用对象置 null 再 update 不会写库，受派人会残留在订单上
        ServiceOrder row = orderRow(orderId);
        assertThat(row.getStaffId()).isNull();
        assertThat(row.getAcceptedAt()).isNull();
        assertThat(occupied()).isEqualTo(1);
    }

    @Test
    @DisplayName("已接单的订单不能再拒接，应先转派或走其他路径")
    void acceptedOrderCannotReject() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);
        accept(orderId);

        mockMvc.perform(act("/reject", orderId, staffAToken, """
                {"reason":"反悔了"}
                """))
                .andExpect(status().isConflict());

        assertThat(statusOf(orderId)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("拒接原因保留在日志里")
    void rejectReasonKeptInLog() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);

        mockMvc.perform(act("/reject", orderId, staffAToken, """
                {"reason":"当天已有其他安排"}
                """))
                .andExpect(status().isOk());

        String reason = jdbcTemplate.queryForObject(
                """
                SELECT reason FROM service_order_log
                WHERE order_id = ? AND action = 'REJECT'
                """, String.class, Long.valueOf(orderId));
        assertThat(reason).contains("已有其他安排");
    }

    // ---------------- 转派 ----------------

    @Test
    @DisplayName("转派后受派人变更且接单时间被清空，新人需重新接单")
    void transferClearsAcceptedAt() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);
        accept(orderId);
        assertThat(orderRow(orderId).getAcceptedAt()).isNotNull();

        mockMvc.perform(act("/transfer", orderId, adminToken, """
                {"newStaffId":%d,"reason":"王服务临时请假"}
                """.formatted(staffBId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.staffId").value(staffBId.toString()))
                // 转派后 acceptedAt 必须为空：新的人还没接
                .andExpect(jsonPath("$.data.acceptedAt").doesNotExist());

        ServiceOrder row = orderRow(orderId);
        assertThat(row.getAcceptedAt()).isNull();
        assertThat(occupied()).isEqualTo(1);
    }

    @Test
    @DisplayName("转派必须填原因")
    void transferRequiresReason() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);

        mockMvc.perform(act("/transfer", orderId, adminToken, """
                {"newStaffId":%d,"reason":""}
                """.formatted(staffBId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("不能转派给当前已受派的人")
    void cannotTransferToSameStaff() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);

        mockMvc.perform(act("/transfer", orderId, adminToken, """
                {"newStaffId":%d,"reason":"重复分派"}
                """.formatted(staffAId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A 转出后不能再操作该订单")
    void transferredStaffCannotOperate() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);
        accept(orderId);

        mockMvc.perform(act("/transfer", orderId, adminToken, """
                {"newStaffId":%d,"reason":"换人"}
                """.formatted(staffBId)))
                .andExpect(status().isOk());

        // A 已不是受派人，接单应失败
        mockMvc.perform(act("/accept", orderId, staffAToken, """
                {"reason":"我还能操作吗"}
                """))
                .andExpect(status().isNotFound());
    }

    /**
     * 验证转出人员无法操作或读取订单，并检查拒绝响应不含当前电话、地址等敏感字段。
     */
    @Test
    @DisplayName("A 转出后读不到该订单，响应不含当前电话与地址")
    void transferredStaffCannotReadCurrentContact() throws Exception {
        String orderId = bookOrder();
        assign(orderId, staffAId);
        // 必须先接单：未接单的 PENDING_ASSIGNMENT 不允许转派（状态机规则），
        // 少了这步 transfer 会返回 409。既有 transferredStaffCannotOperate 也是这么写的。
        accept(orderId);

        // A 在被转出前是受派人，此刻应能读到
        mockMvc.perform(get("/api/service-orders/" + orderId)
                        .header(HEADER, "Bearer " + staffAToken))
                .andExpect(status().isOk());

        // 转派给 B
        mockMvc.perform(act("/transfer", orderId, adminToken, """
                {"newStaffId":%d,"reason":"换人"}
                """.formatted(staffBId)))
                .andExpect(status().isOk());

        // A 已被转出：详情应不可见
        String bodyA = mockMvc.perform(get("/api/service-orders/" + orderId)
                        .header(HEADER, "Bearer " + staffAToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(bodyA)
                .as("转出后旧受派人的响应里不得出现当前联系人电话")
                .doesNotContain("13900000003");
        assertThat(bodyA)
                .as("转出后旧受派人的响应里不得出现当前地址")
                .doesNotContain("分派测试地址");

        // 新受派人 B 应能读到，且拿到的确实是当前联系方式
        String bodyB = mockMvc.perform(get("/api/service-orders/" + orderId)
                        .header(HEADER, "Bearer " + staffBToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(bodyB).as("新受派人应能读到联系方式").contains("13900000003");

        // 旧人的任务列表里也不应再出现该订单
        mockMvc.perform(get("/api/service-orders/my-tasks")
                        .header(HEADER, "Bearer " + staffAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id").value(not(hasItem(orderId))));
    }

    // ---------------- 员工任务列表 ----------------

    @Test
    @DisplayName("服务人员只看到分派给自己的订单")
    void staffSeesOnlyOwnTasks() throws Exception {
        String orderA = bookOrder();
        assign(orderA, staffAId);
        bookOrder();   // 另一个未分派的订单

        mockMvc.perform(get("/api/service-orders/my-tasks")
                        .header(HEADER, "Bearer " + staffAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(orderA));
    }

    @Test
    @DisplayName("老人查不到员工任务列表")
    void elderCannotListTasks() throws Exception {
        mockMvc.perform(get("/api/service-orders/my-tasks")
                        .header(HEADER, "Bearer " + elderToken))
                .andExpect(status().isForbidden());
    }

    // ---------------- 辅助 ----------------

    private String bookOrder() throws Exception {
        String body = mockMvc.perform(post("/api/service-orders")
                        .header(HEADER, "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                                 "address":"分派测试地址","requestId":"%s"}
                                """.formatted(slotId, UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(body);
        m.find();
        return m.group(1);
    }

    private void assign(String orderId, Long staffId) throws Exception {
        mockMvc.perform(act("/assign", orderId, adminToken, """
                {"staffId":%d,"reason":"分派"}
                """.formatted(staffId)))
                .andExpect(status().isOk());
    }

    private void accept(String orderId) throws Exception {
        mockMvc.perform(act("/accept", orderId, staffAToken, """
                {"reason":"收到"}
                """))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder act(
            String action, String orderId, String token, String body) throws Exception {
        return post("/api/service-orders/" + orderId + action)
                .header(HEADER, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(withOrderVersion(orderId, body));
    }

    private int occupied() {
        return slotMapper.selectById(slotId).getOccupiedCount();
    }

    private Long staffIdOf(String orderId) {
        return orderRow(orderId).getStaffId();
    }

    private String statusOf(String orderId) {
        return orderRow(orderId).getStatus();
    }

    /** 直接查库，绕开序列化——验证 NULL 必须看数据库真实值 */
    private ServiceOrder orderRow(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT * FROM service_order WHERE id = ?",
                (rs, i) -> {
                    ServiceOrder o = new ServiceOrder();
                    o.setId(rs.getLong("id"));
                    o.setOrderNo(rs.getString("order_no"));
                    o.setElderId(rs.getLong("elder_id"));
                    o.setSlotId(rs.getLong("slot_id"));
                    o.setServiceId(rs.getLong("service_id"));
                    long staffId = rs.getLong("staff_id");
                    o.setStaffId(rs.wasNull() ? null : staffId);
                    o.setStatus(rs.getString("status"));
                    o.setVersion(rs.getInt("version"));
                    o.setServiceNameSnapshot(rs.getString("service_name_snapshot"));
                    o.setPriceSnapshot(rs.getBigDecimal("price_snapshot"));
                    o.setContactName(rs.getString("contact_name"));
                    o.setContactPhone(rs.getString("contact_phone"));
                    o.setAddressSnapshot(rs.getString("address_snapshot"));
                    o.setSlotStartSnapshot(rs.getTimestamp("slot_start_snapshot").toLocalDateTime());
                    o.setSlotEndSnapshot(rs.getTimestamp("slot_end_snapshot").toLocalDateTime());
                    var acc = rs.getTimestamp("accepted_at");
                    o.setAcceptedAt(acc == null ? null : acc.toLocalDateTime());
                    var st = rs.getTimestamp("started_at");
                    o.setStartedAt(st == null ? null : st.toLocalDateTime());
                    return o;
                }, Long.valueOf(orderId));
    }

    private Long userId(String username) {
        return userMapper.findByUsername(username).getId();
    }
}
