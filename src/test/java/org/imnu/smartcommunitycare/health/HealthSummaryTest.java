package org.imnu.smartcommunitycare.health;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 七日统计测试。
 *
 * <p>重点验证口径正确性，尤其是：
 * <ul>
 *   <li>一天多条时，7 天范围仍是 7 天（不是"最近 7 条"只剩两天）</li>
 *   <li>某天缺测时，该日为空而不是补 0</li>
 *   <li>均值按全部原始样本算，不是日均值再平均</li>
 *   <li>整周无数据的指标返回 null，前端显示"暂无数据"</li>
 * </ul>
 */
@AutoConfigureMockMvc
class HealthSummaryTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ElderProfileMapper elderProfileMapper;

    /**
     * 直接写库造数据，绕过录入接口。
     *
     * <p>统计测试要构造"同一天多条""某天缺测"这类场景，
     * 走接口一条条录既慢又难精确控制日期。
     */
    private void insertRecord(Long elderId, LocalDateTime measuredAt,
                              Integer heartRate, BigDecimal systolic, BigDecimal diastolic) {
        jdbcTemplate.update("""
                INSERT INTO health_record
                  (elder_id, heart_rate, systolic, diastolic, measure_scene, measured_at,
                   mode, source, voided, created_at)
                VALUES (?, ?, ?, ?, 'FASTING', ?, 'HISTORY', 'MANUAL', 0, NOW(3))
                """, elderId, heartRate, systolic, diastolic, measuredAt);
    }

    @Test
    @DisplayName("一天录三条时，七日范围仍是完整七天（不是最近7条）")
    void sevenDayRangeCoversSevenCalendarDays() throws Exception {
        Long elderId = createElder("it_sum_multi");
        createAccount("it_sum_admin1", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_sum_admin1", ADMIN_PASSWORD);
        LocalDate today = LocalDate.now();

        // 今天录 3 条：若按"最近7条"口径，7 天范围里就只剩今天
        insertRecord(elderId, today.atTime(8, 0), 70, new BigDecimal("120.0"), new BigDecimal("78.0"));
        insertRecord(elderId, today.atTime(12, 0), 80, new BigDecimal("125.0"), new BigDecimal("80.0"));
        insertRecord(elderId, today.atTime(18, 0), 90, new BigDecimal("130.0"), new BigDecimal("82.0"));
        // 前 3 天各 1 条
        insertRecord(elderId, today.minusDays(1).atTime(9, 0), 71, new BigDecimal("121.0"), new BigDecimal("79.0"));
        insertRecord(elderId, today.minusDays(2).atTime(9, 0), 72, new BigDecimal("122.0"), new BigDecimal("79.0"));
        insertRecord(elderId, today.minusDays(3).atTime(9, 0), 73, new BigDecimal("123.0"), new BigDecimal("80.0"));

        String body = mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + admin)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.daily.length()").value(7))
                .andReturn().getResponse().getContentAsString();

        // 今天 3 条 + 前 3 天各 1 条 = 6 个样本
        assertThat(body).contains("\"sampleCount\":6");
        // 心率样本数
        assertThat(body).contains("\"metric\":\"heartRate\"");
    }

    @Test
    @DisplayName("某天缺测时该日为空，不补0也不复制前值")
    void missingDayStaysNull() throws Exception {
        Long elderId = createElder("it_sum_gap");
        createAccount("it_sum_admin2", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_sum_admin2", ADMIN_PASSWORD);
        LocalDate today = LocalDate.now();

        insertRecord(elderId, today.atTime(9, 0), 70, new BigDecimal("120.0"), new BigDecimal("78.0"));
        // 昨天与前天都不录 —— 这两天应为空
        insertRecord(elderId, today.minusDays(3).atTime(9, 0), 70, new BigDecimal("120.0"), new BigDecimal("78.0"));

        String body = mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + admin)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.daily.length()").value(7))
                .andReturn().getResponse().getContentAsString();

        // 昨天那天的 heartRate 均值必须是 null（JSON 里为 null 或字段缺省），
        // 不能是 0，也不能是前天的 70
        assertThat(body).doesNotContain("\"sampleCount\":1,\"averages\":{\"heartRate\":70.0,\"systolic\":120.0,\"diastolic\":78.0,\"bloodSugar\":null,\"bodyTemperature\":null,\"sleepHours\":null}}");
        // 全周只有 2 个样本
        assertThat(body).contains("\"sampleCount\":2");
    }

    @Test
    @DisplayName("七日均值按全部原始样本计算，不是日均值再平均")
    void averageUsesAllRawSamples() throws Exception {
        Long elderId = createElder("it_sum_avg");
        createAccount("it_sum_admin3", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_sum_admin3", ADMIN_PASSWORD);
        LocalDate today = LocalDate.now();

        // 今天 3 条：60/70/80；昨天 1 条：100
        // 正确算法：全部 4 个样本求均值 = 77.5
        // 错误算法（日均再平均）：(70 + 100) / 2 = 85
        insertRecord(elderId, today.atTime(8, 0), 60, new BigDecimal("100.0"), new BigDecimal("60.0"));
        insertRecord(elderId, today.atTime(12, 0), 70, new BigDecimal("100.0"), new BigDecimal("60.0"));
        insertRecord(elderId, today.atTime(18, 0), 80, new BigDecimal("100.0"), new BigDecimal("60.0"));
        insertRecord(elderId, today.minusDays(1).atTime(9, 0), 100, new BigDecimal("100.0"), new BigDecimal("60.0"));

        String body = mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + admin)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"sampleCount\":4");
        assertThat(body).contains("\"average\":77.5");
    }

    @Test
    @DisplayName("七天都没有某指标时返回 null，前端显示暂无数据")
    void absentMetricReturnsNulls() throws Exception {
        Long elderId = createElder("it_sum_absent");
        createAccount("it_sum_admin4", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_sum_admin4", ADMIN_PASSWORD);
        LocalDate today = LocalDate.now();

        // 只录心率，不录血糖
        insertRecord(elderId, today.atTime(9, 0), 70, new BigDecimal("120.0"), new BigDecimal("78.0"));

        String body = mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + admin)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // bloodSugar 整周无样本：sampleCount 0，average/max/min 均为 null
        assertThat(body).contains("\"metric\":\"bloodSugar\"");
        assertThat(body).contains("\"sampleCount\":0,\"average\":null,\"max\":null,\"min\":null,\"hitCount\":0");
    }

    @Test
    @DisplayName("跨周边界：8天前的记录不进统计")
    void recordsBeyondSevenDaysExcluded() throws Exception {
        Long elderId = createElder("it_sum_edge");
        createAccount("it_sum_admin5", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_sum_admin5", ADMIN_PASSWORD);
        LocalDate today = LocalDate.now();

        insertRecord(elderId, today.atTime(9, 0), 70, new BigDecimal("120.0"), new BigDecimal("78.0"));
        // 正好 6 天前：在范围内（今天是第7天）
        insertRecord(elderId, today.minusDays(6).atTime(9, 0), 71, new BigDecimal("121.0"), new BigDecimal("79.0"));
        // 7 天前：在范围外
        insertRecord(elderId, today.minusDays(7).atTime(9, 0), 99, new BigDecimal("199.0"), new BigDecimal("199.0"));

        String body = mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + admin)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 范围内 2 条
        assertThat(body).contains("\"sampleCount\":2");
        // 7 天前那条的 199 不应出现
        assertThat(body).doesNotContain("199.0");
    }

    @Test
    @DisplayName("作废记录不参与统计")
    void voidedRecordsExcludedFromSummary() throws Exception {
        Long elderId = createElder("it_sum_void");
        createAccount("it_sum_admin6", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_sum_admin6", ADMIN_PASSWORD);
        LocalDate today = LocalDate.now();

        insertRecord(elderId, today.atTime(9, 0), 70, new BigDecimal("120.0"), new BigDecimal("78.0"));
        insertRecord(elderId, today.minusDays(1).atTime(9, 0), 200, new BigDecimal("300.0"), new BigDecimal("200.0"));
        jdbcTemplate.update("UPDATE health_record SET voided = 1, void_reason = '录错' "
                + "WHERE elder_id = ? AND heart_rate = 200", elderId);

        String body = mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + admin)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"sampleCount\":1");
        // 精确断言：作废那条的收缩压 300 不应出现在 max 里。
        // 不能用 doesNotContain("200")——状态码、"sampleCount" 后面的数字都会误命中。
        assertThat(body).doesNotContain("\"max\":300");
        assertThat(body).contains("\"max\":120");
    }

    @Test
    @DisplayName("老人只能看自己的七日概览")
    void elderCannotReadOthersSummary() throws Exception {
        Long elderB = createElder("it_sum_iso_b");
        createElder("it_sum_iso_a");
        String tokenA = loginAndGetToken(mockMvc, "it_sum_iso_a", ELDER_PASSWORD);

        mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + tokenA)
                        .param("elderId", String.valueOf(elderB)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("老人不传 elderId 时看自己的概览")
    void elderReadsOwnSummary() throws Exception {
        Long elderA = createElder("it_sum_own");
        insertRecord(elderA, LocalDate.now().atTime(9, 0), 75, new BigDecimal("122.0"), new BigDecimal("79.0"));
        String tokenA = loginAndGetToken(mockMvc, "it_sum_own", ELDER_PASSWORD);

        mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.elderId").value(elderA))
                .andExpect(jsonPath("$.data.daily.length()").value(7))
                .andExpect(jsonPath("$.data.metrics.length()").value(6));
    }

    @Test
    @DisplayName("未登录不能查七日概览")
    void anonymousCannotReadSummary() throws Exception {
        mockMvc.perform(get("/api/health-records/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("统计范围起止日期正确标注为今天及前6天")
    void summaryRangeIsSevenDays() throws Exception {
        Long elderId = createElder("it_sum_range");
        createAccount("it_sum_admin7", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_sum_admin7", ADMIN_PASSWORD);
        LocalDate today = LocalDate.now();

        mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + admin)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fromDate").value(today.minusDays(6).toString()))
                .andExpect(jsonPath("$.data.toDate").value(today.toString()))
                .andExpect(jsonPath("$.data.dataAsOf").isNotEmpty());
    }

    @Test
    @DisplayName("七天无任何记录时，各指标样本数为0且均值为null")
    void emptyWeekReturnsZeroSamples() throws Exception {
        Long elderId = createElder("it_sum_empty");
        createAccount("it_sum_admin8", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String admin = loginAndGetToken(mockMvc, "it_sum_admin8", ADMIN_PASSWORD);

        String body = mockMvc.perform(get("/api/health-records/summary")
                        .header("scc-test-token", "Bearer " + admin)
                        .param("elderId", String.valueOf(elderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.daily.length()").value(7))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"sampleCount\":0,\"average\":null,\"max\":null,\"min\":null,\"hitCount\":0");
    }
}
