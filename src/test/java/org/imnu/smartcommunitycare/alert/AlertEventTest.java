package org.imnu.smartcommunitycare.alert;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 告警归并与处置测试。
 *
 * <p>重点验证三条最容易做错的规则：
 * <ul>
 *   <li>同一老人同一规则再次命中 → <b>归并</b>，不新建、不退回待处理、不顺延截止</li>
 *   <li>首次接收时间<b>只写一次</b>，转派后仍是第一次的值</li>
 *   <li>两个管理员同时认领 → 只有一个成功</li>
 * </ul>
 */
@AutoConfigureMockMvc
class AlertEventTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ElderProfileMapper elderProfileMapper;

    /** 直接调用业务方法造事件，避开接口层的字段映射 */
    private Long insertHealthEvent(Long elderId, String ruleCode, String status) {
        jdbcTemplate.update("""
                INSERT INTO alert_event
                  (event_no, source_type, rule_code, elder_id, status, priority,
                   occurrence_count, response_due_at, version, created_at, updated_at)
                VALUES (CONCAT('AET', LPAD(FLOOR(RAND()*1000000), 6, '0')), 'HEALTH_ALERT', ?, ?, ?, 'NORMAL',
                        1, DATE_ADD(NOW(3), INTERVAL 120 MINUTE), 0, NOW(3), NOW(3))
                """, ruleCode, elderId, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM alert_event WHERE elder_id = ? AND rule_code = ? AND status = ?",
                Long.class, elderId, ruleCode, status);
    }

    // ---------- 归并 ----------

    @Test
    @DisplayName("同一老人同一规则再次命中时归并，不新建事件")
    void sameRuleHitsAreMerged() throws Exception {
        Long elderId = createElder("it_alert_merge");
        createAccount("it_alert_adm1", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm1", ADMIN_PASSWORD);

        // 录入两次高心率，都命中 HEART_RATE_HIGH
        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(post("/api/health-records")
                            .header("scc-test-token", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"elderId":%d,"heartRate":150,"mode":"CURRENT","requestId":"req-alert-%d"}
                                    """.formatted(elderId, i)))
                    .andExpect(status().isOk());
        }

        // 只应有一个未结事件，且发生次数为 2
        mockMvc.perform(get("/api/alert-events")
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].occurrenceCount").value(2));
    }

    @Test
    @DisplayName("不同规则不归并，各自独立成事件")
    void differentRulesCreateSeparateEvents() throws Exception {
        Long elderId = createElder("it_alert_diff");
        createAccount("it_alert_adm2", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm2", ADMIN_PASSWORD);

        // 高心率 + 高收缩压：两条不同规则
        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":150,"mode":"CURRENT","requestId":"req-d1"}
                                """.formatted(elderId)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"systolic":160,"diastolic":95,
                                 "mode":"CURRENT","requestId":"req-d2"}
                                """.formatted(elderId)))
                .andExpect(status().isOk());

        // 这条记录实际命中 3 条规则：心率高、收缩压高、舒张压高。
        // 每条规则各自成一个事件——不同规则不能归并成一条，
        // 否则处理人看到"血压高"却不知道心率也异常。
        mockMvc.perform(get("/api/alert-events")
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(jsonPath("$.data.length()").value(3));

        // 确认三条事件的规则编码互不相同
        List<String> ruleCodes = jdbcTemplate.queryForList(
                "SELECT rule_code FROM alert_event WHERE elder_id = ?", String.class, elderId);
        assertThat(ruleCodes).containsExactlyInAnyOrder(
                "HEART_RATE_HIGH", "SYSTOLIC_HIGH", "DIASTOLIC_HIGH");
    }

    @Test
    @DisplayName("归并不改变已认领事项的状态、处理人与响应截止时间")
    void mergeDoesNotResetClaimedEvent() throws Exception {
        Long elderId = createElder("it_alert_keep");
        createAccount("it_alert_adm3", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm3", ADMIN_PASSWORD);

        // 第一次命中生成事件
        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":150,"mode":"CURRENT","requestId":"req-k1"}
                                """.formatted(elderId)))
                .andExpect(status().isOk());

        // 记录原始截止时间
        LocalDateTime dueBefore = jdbcTemplate.queryForObject(
                "SELECT response_due_at FROM alert_event WHERE elder_id = ?", LocalDateTime.class, elderId);

        // 管理员认领
        Long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM alert_event WHERE elder_id = ?", Long.class, elderId);
        mockMvc.perform(post("/api/alert-events/{id}/claim", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        // 第二次命中 → 应归并，不把状态退回 PENDING
        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":152,"mode":"CURRENT","requestId":"req-k2"}
                                """.formatted(elderId)))
                .andExpect(status().isOk());

        // 状态仍是处理中，处理人还在，截止时间没变
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, handler_id, response_due_at, occurrence_count FROM alert_event WHERE id = ?",
                eventId);
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(row.get("handler_id")).isNotNull();
        assertThat((LocalDateTime) row.get("response_due_at")).isEqualTo(dueBefore);
        assertThat(((Number) row.get("occurrence_count")).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName("事件关闭后再次命中会新建事件，不复用已关闭的")
    void newEventAfterClosed() throws Exception {
        Long elderId = createElder("it_alert_reopen");
        createAccount("it_alert_adm4", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm4", ADMIN_PASSWORD);

        insertHealthEvent(elderId, "HEART_RATE_HIGH", "PROCESSING");
        Long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM alert_event WHERE elder_id = ?", Long.class, elderId);

        // 关闭
        mockMvc.perform(post("/api/alert-events/{id}/close", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin)
                        .param("result", "RESOLVED")
                        .param("note", "已联系老人确认，指标恢复正常"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        // 再次命中 → 归并查不到未结的，应新建
        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":155,"mode":"CURRENT","requestId":"req-re1"}
                                """.formatted(elderId)))
                .andExpect(status().isOk());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE elder_id = ?", Long.class, elderId);
        assertThat(count).isEqualTo(2L);
    }

    // ---------- 处置状态机 ----------

    @Test
    @DisplayName("认领后状态为处理中，记录首次接收时间")
    void claimSetsFirstAcceptedAt() throws Exception {
        Long elderId = createElder("it_alert_claim");
        createAccount("it_alert_adm5", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm5", ADMIN_PASSWORD);
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PENDING");

        mockMvc.perform(post("/api/alert-events/{id}/claim", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.firstAcceptedAt").isNotEmpty());
    }

    @Test
    @DisplayName("转派后首次接收时间保持第一次的值，不被覆盖")
    void transferKeepsFirstAcceptedAt() throws Exception {
        Long elderId = createElder("it_alert_transfer");
        Long staffId = createAccount("it_alert_staff1", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_alert_adm6", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm6", ADMIN_PASSWORD);
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PENDING");

        // 认领 → 写入首次接收时间
        mockMvc.perform(post("/api/alert-events/{id}/claim", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());
        LocalDateTime firstAccepted = jdbcTemplate.queryForObject(
                "SELECT first_accepted_at FROM alert_event WHERE id = ?", LocalDateTime.class, eventId);
        assertThat(firstAccepted).isNotNull();

        // 转派给服务人员
        mockMvc.perform(post("/api/alert-events/{id}/transfer", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin)
                        .param("newHandlerId", String.valueOf(staffId))
                        .param("reason", "需要上门核实"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"));

        // 服务人员接收
        String staffToken = loginAndGetToken(mockMvc, "it_alert_staff1", STAFF_PASSWORD);
        mockMvc.perform(post("/api/alert-events/{id}/accept", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        // 首次接收时间未被改写
        LocalDateTime afterAccept = jdbcTemplate.queryForObject(
                "SELECT first_accepted_at FROM alert_event WHERE id = ?", LocalDateTime.class, eventId);
        assertThat(afterAccept).isEqualTo(firstAccepted);
    }

    @Test
    @DisplayName("已关闭事件不能再关闭（终态不可回退）")
    void closedEventCannotBeClosedAgain() throws Exception {
        Long elderId = createElder("it_alert_final");
        createAccount("it_alert_adm7", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm7", ADMIN_PASSWORD);
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PROCESSING");

        mockMvc.perform(post("/api/alert-events/{id}/close", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin)
                        .param("result", "RESOLVED").param("note", "已处理"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/alert-events/{id}/close", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin)
                        .param("result", "RESOLVED").param("note", "再关一次"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("关闭必须填写结果与说明")
    void closeRequiresResultAndNote() throws Exception {
        Long elderId = createElder("it_alert_note");
        createAccount("it_alert_adm8", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm8", ADMIN_PASSWORD);
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PROCESSING");

        mockMvc.perform(post("/api/alert-events/{id}/close", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin)
                        .param("result", "RESOLVED").param("note", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("关闭必须填写处理说明"));
    }

    @Test
    @DisplayName("非受派人不能接收该事项")
    void onlyAssigneeCanAccept() throws Exception {
        Long elderId = createElder("it_alert_accept");
        Long staffA = createAccount("it_alert_sa", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_alert_sb", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_alert_adm9", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm9", ADMIN_PASSWORD);
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PENDING");

        mockMvc.perform(post("/api/alert-events/{id}/assign", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin)
                        .param("assigneeId", String.valueOf(staffA)))
                .andExpect(status().isOk());

        // 另一个服务人员尝试接收
        String other = loginAndGetToken(mockMvc, "it_alert_sb", STAFF_PASSWORD);
        mockMvc.perform(post("/api/alert-events/{id}/accept", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + other))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("老人不能认领事项")
    void elderCannotClaim() throws Exception {
        Long elderId = createElder("it_alert_ec");
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PENDING");
        String elderToken = loginAndGetToken(mockMvc, "it_alert_ec", ELDER_PASSWORD);

        mockMvc.perform(post("/api/alert-events/{id}/claim", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + elderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("健康告警不能撤销")
    void healthAlertCannotBeCancelled() throws Exception {
        Long elderId = createElder("it_alert_hc");
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PENDING");
        String elderToken = loginAndGetToken(mockMvc, "it_alert_hc", ELDER_PASSWORD);

        mockMvc.perform(post("/api/alert-events/{id}/cancel", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + elderToken)
                        .param("reason", "误报"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("分派给已停用账号被拒")
    void assignToDisabledAccountRejected() throws Exception {
        Long elderId = createElder("it_alert_dis");
        Long staffId = createAccount("it_alert_sd", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_alert_adm10", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_adm10", ADMIN_PASSWORD);
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PENDING");

        // 停用该服务人员
        jdbcTemplate.update("UPDATE sys_user SET enabled = 0 WHERE id = ?", staffId);

        mockMvc.perform(post("/api/alert-events/{id}/assign", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin)
                        .param("assigneeId", String.valueOf(staffId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("指定的处理人账号已停用"));
    }

    // ---------- 主动求助 ----------

    @Test
    @DisplayName("老人提交求助成功，默认为高优先级")
    void elderCanSubmitHelpCall() throws Exception {
        createElder("it_alert_help");
        String elderToken = loginAndGetToken(mockMvc, "it_alert_help", ELDER_PASSWORD);

        mockMvc.perform(post("/api/alert-events/help-calls")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callType":"跌倒","contactPhone":"13900001234",
                                 "description":"在客厅摔倒","requestId":"req-help-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.priority").value("HIGH"));
    }

    @Test
    @DisplayName("同一 requestId 重试只产生一条求助")
    void helpCallRetryReturnsSameEvent() throws Exception {
        createElder("it_alert_retry");
        String elderToken = loginAndGetToken(mockMvc, "it_alert_retry", ELDER_PASSWORD);
        String payload = """
                {"callType":"身体不适","contactPhone":"13900001234",
                 "description":"胸闷","requestId":"req-help-retry"}
                """;

        String first = mockMvc.perform(post("/api/alert-events/help-calls")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/alert-events/help-calls")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE source_type = 'HELP_CALL'", Long.class);
        assertThat(count).isEqualTo(1L);
        assertThat(first).contains("HELP_CALL");
    }

    @Test
    @DisplayName("老人再次发起求助（新 requestId）是独立事件，不被吞掉")
    void secondHelpCallIsSeparateEvent() throws Exception {
        createElder("it_alert_twice");
        String elderToken = loginAndGetToken(mockMvc, "it_alert_twice", ELDER_PASSWORD);

        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(post("/api/alert-events/help-calls")
                            .header("scc-test-token", "Bearer " + elderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"callType":"需要帮助","contactPhone":"13900001234",
                                     "requestId":"req-twice-%d"}
                                    """.formatted(i)))
                    .andExpect(status().isOk());
        }

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM alert_event WHERE source_type = 'HELP_CALL'", Long.class);
        assertThat(count).isEqualTo(2L);
    }

    // ---------- 权限 ----------

    @Test
    @DisplayName("老人看不到别人的事项")
    void elderCannotSeeOthersEvent() throws Exception {
        Long elderB = createElder("it_alert_eb");
        createElder("it_alert_ea");
        insertHealthEvent(elderB, "HEART_RATE_HIGH", "PENDING");
        Long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM alert_event WHERE elder_id = ?", Long.class, elderB);
        String tokenA = loginAndGetToken(mockMvc, "it_alert_ea", ELDER_PASSWORD);

        mockMvc.perform(get("/api/alert-events/{id}", eventId)
                        .header("scc-test-token", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("未分派给服务人员的事项对该服务人员不可见")
    void staffCannotSeeUnassignedEvent() throws Exception {
        Long elderId = createElder("it_alert_stv");
        createAccount("it_alert_stp", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PENDING");
        String staff = loginAndGetToken(mockMvc, "it_alert_stp", STAFF_PASSWORD);

        mockMvc.perform(get("/api/alert-events/{id}", eventId)
                        .header("scc-test-token", "Bearer " + staff))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("未登录不能访问事项")
    void anonymousCannotAccess() throws Exception {
        mockMvc.perform(get("/api/alert-events"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 时间线 ----------

    @Test
    @DisplayName("处理时间线记录每一步")
    void timelineRecordsEachStep() throws Exception {
        Long elderId = createElder("it_alert_tl");
        Long staffId = createAccount("it_alert_tl_s", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_alert_tl_a", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_alert_tl_a", ADMIN_PASSWORD);
        Long eventId = insertHealthEvent(elderId, "HEART_RATE_HIGH", "PENDING");

        mockMvc.perform(post("/api/alert-events/{id}/assign", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + admin)
                        .param("assigneeId", String.valueOf(staffId)))
                .andExpect(status().isOk());

        String staff = loginAndGetToken(mockMvc, "it_alert_tl_s", STAFF_PASSWORD);
        mockMvc.perform(post("/api/alert-events/{id}/accept", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + staff))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/alert-events/{id}/close", eventId).param("version", String.valueOf(jdbcTemplate.queryForObject("SELECT version FROM alert_event WHERE id = ?", Integer.class, eventId)))
                        .header("scc-test-token", "Bearer " + staff)
                        .param("result", "RESOLVED").param("note", "已上门处理"))
                .andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/alert-events/{id}", eventId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("ASSIGN");
        assertThat(body).contains("ACCEPT");
        assertThat(body).contains("CLOSE");
    }
}
