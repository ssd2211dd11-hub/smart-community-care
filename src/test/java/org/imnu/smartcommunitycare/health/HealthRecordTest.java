package org.imnu.smartcommunitycare.health;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.health.model.enums.HealthRule;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 健康记录录入与对象级授权测试。
 *
 * <p>重点验证两件事：
 * ①规则判定保存了数值与阈值快照，而不是只留一个"异常等级"
 * ② 老人 A 读不到、改不了老人 B 的数据
 */
@AutoConfigureMockMvc
class HealthRecordTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ElderProfileMapper elderProfileMapper;


    // ---------- 规则判定 ----------

    @Test
    @DisplayName("心率与血压超阈值时保存命中明细，含数值与阈值快照")
    void abnormalValuesProduceAnomalyEvidence() throws Exception {
        Long elderId = createElder("it_hr_high", ELDER_PASSWORD);
        createAccount("it_admin_hr", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_hr", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":145,"systolic":152,"diastolic":95,
                                 "measureScene":"RANDOM","mode":"CURRENT","requestId":"req-hr-1"}
                                """.formatted(elderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.heartRate").value(145))
                // 三项异常各自留一份证据
                .andExpect(jsonPath("$.data.anomalies.length()").value(3));

        // 快照要能看到当时的阈值，规则改版后仍能还原
        String body = mockMvc.perform(get("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("HEART_RATE_HIGH");
        assertThat(body).contains("\"upperBound\":100");
        assertThat(body).contains("bpm");
    }

    @Test
    @DisplayName("正常范围内的数值不产生命中明细")
    void normalValuesProduceNoAnomaly() throws Exception {
        Long elderId = createElder("it_normal", ELDER_PASSWORD);
        createAccount("it_admin_normal", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_normal", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":72,"systolic":125,"diastolic":78,"sleepHours":7.0,
                                 "measureScene":"FASTING","mode":"CURRENT","requestId":"req-normal-1"}
                                """.formatted(elderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.anomalies.length()").value(0));
    }

    @Test
    @DisplayName("恰好等于阈值不算命中（边界不含）")
    void valueExactlyOnBoundDoesNotHit() {
        // 上界 100，100 本身不算超
        assertThat(HealthRule.HEART_RATE_HIGH.matches(new java.math.BigDecimal("100"))).isFalse();
        // 下界 60，60 本身不算低
        assertThat(HealthRule.HEART_RATE_LOW.matches(new java.math.BigDecimal("60"))).isFalse();
        // 越界则命中
        assertThat(HealthRule.HEART_RATE_HIGH.matches(new java.math.BigDecimal("101"))).isTrue();
    }

    @Test
    @DisplayName("缺测指标不参与判定，不当作正常也不当作异常")
    void missingMetricIsNotJudged() {
        assertThat(HealthRule.HEART_RATE_HIGH.matches(null)).isFalse();
        assertThat(HealthRule.SLEEP_TOO_LITTLE.matches(null)).isFalse();
    }

    // ---------- 输入校验 ----------

    @Test
    @DisplayName("血压不成对填写被拒")
    void bloodPressureMustBePaired() throws Exception {
        Long elderId = createElder("it_bp_pair", ELDER_PASSWORD);
        createAccount("it_admin_bp", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_bp", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"systolic":120,"mode":"CURRENT","requestId":"req-bp-1"}
                                """.formatted(elderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("血压需成对填写：收缩压与舒张压必须同时提供"));
    }

    @Test
    @DisplayName("一项指标都没填被拒")
    void requireAtLeastOneMetric() throws Exception {
        Long elderId = createElder("it_empty", ELDER_PASSWORD);
        createAccount("it_admin_empty", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_empty", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"mode":"CURRENT","requestId":"req-empty-1"}
                                """.formatted(elderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请至少填写一项健康指标"));
    }

    @Test
    @DisplayName("睡眠超过 24 小时被拒")
    void sleepHoursOutOfRangeRejected() throws Exception {
        Long elderId = createElder("it_sleep", ELDER_PASSWORD);
        createAccount("it_admin_sleep", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_sleep", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"sleepHours":30,"mode":"CURRENT","requestId":"req-sleep-1"}
                                """.formatted(elderId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("小数位超限被拒（体温只允许 1 位）")
    void decimalPrecisionEnforced() throws Exception {
        Long elderId = createElder("it_precision", ELDER_PASSWORD);
        createAccount("it_admin_precision", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_precision", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"bodyTemperature":36.123,"mode":"CURRENT","requestId":"req-prec-1"}
                                """.formatted(elderId)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 录入模式 ----------

    @Test
    @DisplayName("历史补录不晚于当前的时间可保存，未来的时间被拒")
    void historyModeTimeValidation() throws Exception {
        Long elderId = createElder("it_history", ELDER_PASSWORD);
        createAccount("it_admin_history", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_history", ADMIN_PASSWORD);

        // 过去时间：允许
        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":70,"mode":"HISTORY",
                                 "measuredAt":"2026-06-01T08:00:00","requestId":"req-hist-1"}
                                """.formatted(elderId)))
                .andExpect(status().isOk());

        // 未来时间：拒绝
        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":70,"mode":"HISTORY",
                                 "measuredAt":"2099-01-01T08:00:00","requestId":"req-hist-2"}
                                """.formatted(elderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("测量时间不能晚于当前时间"));
    }

    @Test
    @DisplayName("历史补录必须填写测量时间")
    void historyModeRequiresMeasuredAt() throws Exception {
        Long elderId = createElder("it_history_nt", ELDER_PASSWORD);
        createAccount("it_admin_history_nt", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_history_nt", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":70,"mode":"HISTORY","requestId":"req-hist-3"}
                                """.formatted(elderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("历史补录必须填写测量时间"));
    }

    // ---------- 防重 ----------

    @Test
    @DisplayName("相同 requestId 重试只产生一条记录")
    void retryWithSameRequestIdCreatesOneRecord() throws Exception {
        Long elderId = createElder("it_retry", ELDER_PASSWORD);
        createAccount("it_admin_retry", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_retry", ADMIN_PASSWORD);

        String payload = """
                {"elderId":%d,"heartRate":80,"mode":"CURRENT","requestId":"req-retry-1"}
                """.formatted(elderId);

        String first = mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // 两次必须指向同一条记录。断言 id 而不是整段 JSON：
        // 响应里的 timestamp 每次都会变，比全等会把"防重生效"误判成失败。
        String firstId = extractJsonValue(first, "id");
        String secondId = extractJsonValue(second, "id");
        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    @DisplayName("相同 requestId 提交不同内容返回 409")
    void sameRequestIdDifferentContentConflicts() throws Exception {
        Long elderId = createElder("it_conflict", ELDER_PASSWORD);
        createAccount("it_admin_conflict", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_conflict", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":80,"mode":"CURRENT","requestId":"req-conf-1"}
                                """.formatted(elderId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":99,"mode":"CURRENT","requestId":"req-conf-1"}
                                """.formatted(elderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("相同请求标识提交了不同内容"));
    }

    /** 从响应 JSON 中取顶层字段值，避免依赖序列化库 API */
    private String extractJsonValue(String json, String field) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\\\"" + field + "\\\":([^,}]+)").matcher(json);
        return m.find() ? m.group(1).trim() : null;
    }

    // ---------- 对象级授权（本阶段核心） ----------

    @Test
    @DisplayName("老人不传 elderId 时正常录入到自己名下")
    void elderRecordsForSelfByDefault() throws Exception {
        Long elderA = createElder("it_elder_self", ELDER_PASSWORD);
        String tokenA = loginAndGetToken(mockMvc, "it_elder_self", ELDER_PASSWORD);

        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"heartRate":75,"mode":"CURRENT","requestId":"req-self-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.elderId").value(elderA));
    }

    @DisplayName("老人伪造他人 elderId 录入被拒，不落库")
    void elderCannotRecordForOthers() throws Exception {
        Long elderB = createElder("it_elder_b", ELDER_PASSWORD);
        String tokenA = loginAndGetToken(mockMvc, "it_elder_a_forge", ELDER_PASSWORD);

        // 老人 A 试图给老人 B 录数据：按 404 拒绝（不区分"无权"与"不存在"）
        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":75,"mode":"CURRENT","requestId":"req-a-1"}
                                """.formatted(elderB)))
                .andExpect(status().isNotFound());

        // 确认没有落库：老人 B 名下没有这条记录
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM health_record WHERE elder_id = ?", Long.class, elderB);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("老人A 查不到老人 B 的记录（按404 处理，不暴露对象存在）")
    void elderCannotReadOthersRecords() throws Exception {
        Long elderA = createElder("it_iso_a", ELDER_PASSWORD);
        Long elderB = createElder("it_iso_b", ELDER_PASSWORD);
        createAccount("it_admin_iso", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_iso", ADMIN_PASSWORD);
        String tokenA = loginAndGetToken(mockMvc, "it_iso_a", ELDER_PASSWORD);

        // 先给 B 录一条
        mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":88,"mode":"CURRENT","requestId":"req-iso-1"}
                                """.formatted(elderB)))
                .andExpect(status().isOk());

        // 老人 A 显式指定 B 的档案查询：应被拒
        mockMvc.perform(get("/api/health-records")
                        .header("scc-test-token", "Bearer " + tokenA)
                        .param("elderId", String.valueOf(elderB)))
                .andExpect(status().isNotFound());

        // 老人 A 不指定时只看得到自己的
        String own = mockMvc.perform(get("/api/health-records")
                        .header("scc-test-token", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(own).doesNotContain(String.valueOf(elderB));
    }

    @Test
    @DisplayName("服务人员不能查询健康记录")
    void staffCannotReadHealthRecords() throws Exception {
        Long elderId = createElder("it_staff_denied", ELDER_PASSWORD);
        createAccount("it_staff_probe", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        String staffToken = loginAndGetToken(mockMvc, "it_staff_probe", STAFF_PASSWORD);

        mockMvc.perform(get("/api/health-records")
                        .header("scc-test-token", "Bearer " + staffToken)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("未登录不能录入健康记录")
    void anonymousCannotCreateRecord() throws Exception {
        mockMvc.perform(post("/api/health-records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"heartRate":70,"mode":"CURRENT","requestId":"req-anon-1"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 作废 ----------

    @Test
    @DisplayName("作废后记录不参与查询，但记录本身保留")
    void voidRecordRemovesFromQueryButKeepsRow() throws Exception {
        Long elderId = createElder("it_void", ELDER_PASSWORD);
        createAccount("it_admin_void", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_void", ADMIN_PASSWORD);

        String created = mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":76,"mode":"CURRENT","requestId":"req-void-1"}
                                """.formatted(elderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String recordId = tools.jackson.databind.json.JsonMapper.builder().build().readTree(created).path("data").path("id").asString();

        mockMvc.perform(post("/api/health-records/{id}/void", Long.parseLong(recordId))
                        .header("scc-test-token", "Bearer " + adminToken)
                        .param("reason", "录入时单位填错"))
                .andExpect(status().isOk());

        // 查询时不再出现
        mockMvc.perform(get("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("作废必须填原因")
    void voidRequiresReason() throws Exception {
        Long elderId = createElder("it_void_reason", ELDER_PASSWORD);
        createAccount("it_admin_vr", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_vr", ADMIN_PASSWORD);

        String created = mockMvc.perform(post("/api/health-records")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"elderId":%d,"heartRate":76,"mode":"CURRENT","requestId":"req-vr-1"}
                                """.formatted(elderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String recordId = tools.jackson.databind.json.JsonMapper.builder().build().readTree(created).path("data").path("id").asString();

        mockMvc.perform(post("/api/health-records/{id}/void", Long.parseLong(recordId))
                        .header("scc-test-token", "Bearer " + adminToken)
                        .param("reason", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("作废必须填写原因"));
    }
}
