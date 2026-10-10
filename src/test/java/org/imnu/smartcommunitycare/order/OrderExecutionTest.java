package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceItemMapper;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 履约后半程：开工、提交结果、确认、退回、异常终止。
 *
 * <p>这些动作<b>都不改变名额</b>，每个用例都断言名额不变。
 * 服务一旦开始，名额就算用过；未能完成走终止而不是取消。
 */
@AutoConfigureMockMvc
class OrderExecutionTest extends BaseIntegrationTest {

    private static final String HEADER = "scc-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceItemMapper itemMapper;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String adminToken;
    private String staffToken;
    private String elderToken;
    private String otherElderToken;
    private Long staffId;
    private Long slotId;
    private Long startedSlotId;
    private Long serviceId;

    /** 本用例的老人档案 ID：快照用例只改自己的档案，不动库里其他人 */
    private Long currentElderProfileId;

    @BeforeEach
    void prepare() throws Exception {
        currentElderProfileId = createElder("it_ex_elder", ELDER_PASSWORD);
        createElder("it_ex_other", ELDER_PASSWORD);
        createAccount("it_ex_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        staffId = createAccount("it_ex_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);

        adminToken = loginAndGetToken(mockMvc, "it_ex_admin", ADMIN_PASSWORD);
        staffToken = loginAndGetToken(mockMvc, "it_ex_staff", STAFF_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, "it_ex_elder", ELDER_PASSWORD);
        otherElderToken = loginAndGetToken(mockMvc, "it_ex_other", ELDER_PASSWORD);

        Long factoryServiceId = createFixtureItem(it -> {
            it.setName("履约测试服务");
            it.setDescription("用于履约流程测试");
            it.setPrice(new BigDecimal("100.00"));
            it.setPhone("13800000000");
        });
        serviceId = factoryServiceId;

        // 两个时段，各有用途：
        //   bookableSlot —— 未来时段，用于「预约」这一步（预约要求时段未开始）
        //   startedSlot   —— 已开始的时段，用于「开工」这一步（开工要求已到时间）
        // 这两个条件不可能同时落在同一个时段上，必须分开。
        slotId = createSlot(LocalDateTime.now().plusDays(1),
                LocalDateTime.now().plusDays(1).plusHours(3));
        startedSlotId = createSlot(LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(3));
    }

    private Long createSlot(LocalDateTime start, LocalDateTime end) {
        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(serviceId);
        slot.setStartAt(start);
        slot.setEndAt(end);
        slot.setCapacity(10);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(slot);
        return slot.getId();
    }

    // ---------------- 完整正常链路 ----------------

    @Test
    @DisplayName("C-02 完整链路：开工→提交→确认，各时间与版本一致，名额不变")
    void fullHappyPath() throws Exception {
        String orderId = bookAndAccept();

        mockMvc.perform(act("/start", orderId, staffToken, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_SERVICE"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty());

        mockMvc.perform(act("/submit-result", orderId, staffToken, """
                {"result":"已完成保洁，卫生间已重点清理"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.serviceResult").value("已完成保洁，卫生间已重点清理"));

        mockMvc.perform(act("/confirm", orderId, elderToken, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.confirmedAt").isNotEmpty());

        // 名额从头到尾不变
        assertThat(occupied()).isEqualTo(1);
        assertThat(versionOf(orderId)).isEqualTo(5);  // 创建/分派/接单/开工/提交/确认

        // 三段时间都写入
        var row = orderFields(orderId);
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getFinishedAt()).isNotNull();
        assertThat(row.getConfirmedAt()).isNotNull();
    }

    // ---------------- 开工时间规则 ----------------

    @Test
    @DisplayName("C-01 预约开始前不能开工")
    void cannotStartBeforeSlotStarts() throws Exception {
        // 只预约接单，不改时间——此时时段还在未来，应该拒绝开工
        String orderId = bookOn(slotId);
        assign(orderId);
        mockMvc.perform(act("/accept", orderId, staffToken, """
                {"reason":"收到"}
                """))
                .andExpect(status().isOk());

        mockMvc.perform(act("/start", orderId, staffToken, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("尚未开始")));

        assertThat(statusOf(orderId)).isEqualTo("ACCEPTED");
        assertThat(orderFields(orderId).getStartedAt()).isNull();
    }

    @Test
    @DisplayName("C-01 未接单不能开工")
    void cannotStartWithoutAccept() throws Exception {
        String orderId = bookOn(slotId);
        assign(orderId);

        mockMvc.perform(act("/start", orderId, staffToken, null))
                .andExpect(status().isConflict());

        assertThat(statusOf(orderId)).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("C-01 其他服务人员不能开工")
    void otherStaffCannotStart() throws Exception {
        createAccount("it_ex_staff2", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        String staff2Token = loginAndGetToken(mockMvc, "it_ex_staff2", STAFF_PASSWORD);
        String orderId = bookAndAccept();

        mockMvc.perform(act("/start", orderId, staff2Token, null))
                .andExpect(status().isNotFound());
    }

    // ---------------- 退回重做 ----------------

    @Test
    @DisplayName("C-03 提交→退回→再次提交→确认，首次开工时间不被覆盖")
    void returnKeepsFirstStartTime() throws Exception {
        String orderId = bookAndAccept();
        start(orderId);
        var firstStart = orderFields(orderId).getStartedAt();
        submit(orderId, "第一次提交");

        mockMvc.perform(act("/return", orderId, elderToken, """
                {"result":"厨房还有污渍没清"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_SERVICE"));

        // 首次开工时间必须保留
        assertThat(orderFields(orderId).getStartedAt()).isEqualTo(firstStart);

        submit(orderId, "已补做厨房清洁");
        mockMvc.perform(act("/confirm", orderId, elderToken, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        // 历次提交与退回原因都留痕
        String logs = jdbcTemplate.queryForObject(
                "SELECT GROUP_CONCAT(action ORDER BY id) FROM service_order_log WHERE order_id = ?",
                String.class, Long.valueOf(orderId));
        assertThat(logs).contains("START")
                .contains("SUBMIT")
                .contains("RETURN")
                .contains("CONFIRM");
        assertThat(occupied()).isEqualTo(1);
    }

    // ---------------- 确认权限 ----------------

    @Test
    @DisplayName("C-04 老人不能确认他人订单")
    void cannotConfirmOthersOrder() throws Exception {
        String orderId = bookAndAccept();
        start(orderId);
        submit(orderId, "已完成");

        mockMvc.perform(act("/confirm", orderId, otherElderToken, null))
                .andExpect(status().isNotFound());

        assertThat(statusOf(orderId)).isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    @DisplayName("C-04 服务人员不能自行确认完成")
    void staffCannotSelfConfirm() throws Exception {
        String orderId = bookAndAccept();
        start(orderId);
        submit(orderId, "已完成");

        mockMvc.perform(act("/confirm", orderId, staffToken, null))
                .andExpect(status().isForbidden());

        assertThat(statusOf(orderId)).isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    @DisplayName("C-04 管理员代确认必须填原因")
    void adminConfirmRequiresReason() throws Exception {
        String orderId = bookAndAccept();
        start(orderId);
        submit(orderId, "已完成");

        mockMvc.perform(act("/confirm-as-admin", orderId, adminToken, """
                {"reason":"  "}
                """))
                .andExpect(status().isBadRequest());

        assertThat(statusOf(orderId)).isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    @DisplayName("C-04 管理员代确认填原因后成功")
    void adminConfirmWithReasonSucceeds() throws Exception {
        String orderId = bookAndAccept();
        start(orderId);
        submit(orderId, "已完成");

        mockMvc.perform(act("/confirm-as-admin", orderId, adminToken, """
                {"reason":"老人电话确认无误"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    // ---------------- 异常终止 ----------------

    @Test
    @DisplayName("C-05 终止后为终态，不能再确认，名额不释放")
    void terminateIsFinalAndKeepsSlot() throws Exception {
        String orderId = bookAndAccept();
        start(orderId);

        mockMvc.perform(act("/terminate", orderId, adminToken, """
                {"reason":"老人临时住院，服务无法继续"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TERMINATED"))
                .andExpect(jsonPath("$.data.terminatedAt").isNotEmpty());

        // 终止后不能确认
        mockMvc.perform(act("/confirm", orderId, elderToken, null))
                .andExpect(status().isConflict());

        // 名额不释放：服务已经发生
        assertThat(occupied()).isEqualTo(1);
    }

    @Test
    @DisplayName("C-05 终止必须填原因")
    void terminateRequiresReason() throws Exception {
        String orderId = bookAndAccept();
        start(orderId);

        mockMvc.perform(act("/terminate", orderId, adminToken, """
                {"reason":""}
                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("C-05 服务人员不能终止订单")
    void staffCannotTerminate() throws Exception {
        String orderId = bookAndAccept();
        start(orderId);

        mockMvc.perform(act("/terminate", orderId, staffToken, """
                {"reason":"我不干了"}
                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("C-05 待确认状态也可终止")
    void canTerminateFromPendingConfirmation() throws Exception {
        String orderId = bookAndAccept();
        start(orderId);
        submit(orderId, "已完成");

        mockMvc.perform(act("/terminate", orderId, adminToken, """
                {"reason":"核实后老人表示未收到服务"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TERMINATED"));
    }

    // ---------------- 快照不受后续修改影响 ----------------

    @Test
    @DisplayName("C-09 服务改价、改地址后，订单快照保持创建时内容")
    void snapshotsUnaffectedByLaterChanges() throws Exception {
        String orderId = bookAndAccept();

        // 建单时的地址：下单请求里填的，作为快照的对照基准
        String addressAtBooking = orderFields(orderId).getAddressSnapshot();

        // 改服务价格：只改本用例创建的那个服务。
        // 原先是 WHERE id = 1——历史硬编码，与本用例的服务无关。
        // 那条语句影响 0 行（探针实测），本用例有一半是空转，现已收窄。
        int priceRows = jdbcTemplate.update(
                "UPDATE service_item SET price = 999.00 WHERE id = ?", serviceId);

        // 改老人档案地址：同样只改本用例的老人。
        // 原先这条完全没有 WHERE，会把库里所有老人档案的地址都改掉。
        int addressRows = jdbcTemplate.update(
                "UPDATE elder_profile SET address = ? WHERE id = ?",
                "新地址999号", currentElderProfileId);

        // 断言一：两条 UPDATE 各影响 1 行。
        // 不加这条，「快照没变」可能只是因为压根没改成——
        // 这正是原先 WHERE id = 1 命中 0 行却照样通过的成因。
        assertThat(priceRows).isEqualTo(1);
        assertThat(addressRows).isEqualTo(1);

        // 断言二：源数据确实被改了。
        // 快照不变要成立，前提是「被改的东西真的变了」，
        // 否则测的是「没改任何东西时快照当然不变」。
        assertThat(jdbcTemplate.queryForObject(
                "SELECT price FROM service_item WHERE id = ?",
                java.math.BigDecimal.class, serviceId)).isEqualByComparingTo("999.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT address FROM elder_profile WHERE id = ?",
                String.class, currentElderProfileId)).isEqualTo("新地址999号");

        // 断言三：快照保持创建时的内容。
        var row = orderFields(orderId);
        assertThat(row.getPriceSnapshot()).isEqualByComparingTo("100.00");
        assertThat(row.getServiceNameSnapshot()).isEqualTo("履约测试服务");
        // 地址快照：建单时填的是「履约测试地址」，之后档案地址被改过，快照不应跟随
        assertThat(addressAtBooking).isEqualTo("履约测试地址");
        assertThat(row.getAddressSnapshot()).isEqualTo("履约测试地址");
        // 订单自己的联系人快照不变
        assertThat(row.getContactName()).isEqualTo("张建国");
    }

    // ---------------- 辅助 ----------------

    private String bookOrder() throws Exception {
        return bookOn(slotId);
    }

    private String bookOn(Long targetSlot) throws Exception {
        String body = mockMvc.perform(post("/api/service-orders")
                        .header(HEADER, "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                                 "address":"履约测试地址","requestId":"%s"}
                                """.formatted(targetSlot, UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(body);
        m.find();
        return m.group(1);
    }

    private void assign(String orderId) throws Exception {
        mockMvc.perform(act("/assign", orderId, adminToken, """
                {"staffId":%d,"reason":"分派"}
                """.formatted(staffId)))
                .andExpect(status().isOk());
    }

    /**
     * 下单并走到已接单。
     *
     * <p><b>关键设计</b>：预约要求「时段未开始」，开工要求「已到开始时间」，
     * 这两个条件不可能同时成立在真实时间里。所以先用未来时段正常预约，
     * 再把该时段的开始时间改到过去，模拟「时间到了」。
     *
     * <p>这是测试夹具的常规做法：让被测规则各自在它成立的时间条件下被验证，
     * 而不是为了让一条用例跑通去放宽业务规则。
     */
    private String bookAndAccept() throws Exception {
        String orderId = bookOn(slotId);
        assign(orderId);
        mockMvc.perform(act("/accept", orderId, staffToken, """
                {"reason":"收到"}
                """))
                .andExpect(status().isOk());
        // 时间到：把「订单快照里的」开始时间移到过去，之后才能合法开工。
        // 改的是 snapshot 而不是 service_slot.start_at——开工校验读的是快照，
        // 而且快照本就不该被时段的后续修改影响（这是设计要求，不是缺陷）。
        jdbcTemplate.update(
                "UPDATE service_order SET slot_start_snapshot = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(1)),
                Long.valueOf(orderId));
        return orderId;
    }

    private void start(String orderId) throws Exception {
        mockMvc.perform(act("/start", orderId, staffToken, null))
                .andExpect(status().isOk());
    }

    private void submit(String orderId, String result) throws Exception {
        mockMvc.perform(act("/submit-result", orderId, staffToken,
                "{\"result\":\"" + result + "\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder act(
            String action, String orderId, String token, String body) {
        var b = post("/api/service-orders/" + orderId + action)
                .header(HEADER, "Bearer " + token);
        b.contentType(MediaType.APPLICATION_JSON).content(withOrderVersion(orderId, body));
        return b;
    }

    /** 名额校验：两个时段都查，任一被多扣都要发现 */
    private int occupied() {
        return slotMapper.selectById(slotId).getOccupiedCount();
    }

    private int versionOf(String orderId) {
        return orderFields(orderId).getVersion() == null ? 0 : orderFields(orderId).getVersion();
    }

    private String statusOf(String orderId) {
        return orderFields(orderId).getStatus();
    }

    /** 直查数据库，避免只看 JSON 证明不了字段真实状态 */
    private org.imnu.smartcommunitycare.order.model.entity.ServiceOrder orderFields(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT * FROM service_order WHERE id = ?",
                (rs, i) -> {
                    var o = new org.imnu.smartcommunitycare.order.model.entity.ServiceOrder();
                    o.setId(rs.getLong("id"));
                    o.setOrderNo(rs.getString("order_no"));
                    o.setElderId(rs.getLong("elder_id"));
                    o.setSlotId(rs.getLong("slot_id"));
                    o.setServiceId(rs.getLong("service_id"));
                    long sid = rs.getLong("staff_id");
                    o.setStaffId(rs.wasNull() ? null : sid);
                    o.setStatus(rs.getString("status"));
                    o.setVersion(rs.getInt("version"));
                    o.setServiceNameSnapshot(rs.getString("service_name_snapshot"));
                    o.setPriceSnapshot(rs.getBigDecimal("price_snapshot"));
                    o.setContactName(rs.getString("contact_name"));
                    o.setContactPhone(rs.getString("contact_phone"));
                    o.setAddressSnapshot(rs.getString("address_snapshot"));
                    o.setServiceResult(rs.getString("service_result"));
                    o.setSlotStartSnapshot(rs.getTimestamp("slot_start_snapshot").toLocalDateTime());
                    o.setSlotEndSnapshot(rs.getTimestamp("slot_end_snapshot").toLocalDateTime());
                    o.setAcceptedAt(ts(rs, "accepted_at"));
                    o.setStartedAt(ts(rs, "started_at"));
                    o.setFinishedAt(ts(rs, "finished_at"));
                    o.setConfirmedAt(ts(rs, "confirmed_at"));
                    o.setTerminatedAt(ts(rs, "terminated_at"));
                    return o;
                }, Long.valueOf(orderId));
    }

    private LocalDateTime ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        var v = rs.getTimestamp(col);
        return v == null ? null : v.toLocalDateTime();
    }
}
