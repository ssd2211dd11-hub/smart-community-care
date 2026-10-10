package org.imnu.smartcommunitycare.health.service;

import org.imnu.smartcommunitycare.health.mapper.HealthRecordAnomalyMapper;
import org.imnu.smartcommunitycare.health.mapper.HealthRecordMapper;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecord;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecordAnomaly;
import org.imnu.smartcommunitycare.health.model.enums.HealthMetric;
import org.imnu.smartcommunitycare.health.model.response.HealthSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 七日健康统计。
 *
 * <p><b>与"最近 7 条"的区别</b>：这里按自然日分组。一天测三次时，
 * "最近 7 条"只剩两天多，趋势图会骗人。
 *
 * <p>统计一律在内存中完成：单社区演示规模下七天数据量有限，
 * 换来的是口径清晰、可单元测试。为此付出 SQL 复杂度的代价是值得的。
 */
@Service
@RequiredArgsConstructor
public class HealthSummaryService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HealthRecordMapper healthRecordMapper;
    private final HealthRecordAnomalyMapper anomalyMapper;
    private final Clock clock;

    /**
     * 计算某位老人最近七天的健康概览。
     *
     * @param elderId 老人档案 ID（调用方须已完成访问权校验）
     */
    @Transactional(readOnly = true)
    public HealthSummaryResponse summarize(Long elderId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate from = today.minusDays(6);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        List<HealthRecord> records = healthRecordMapper.findInRange(elderId, start, end);

        // 按指标聚合全部原始样本
        Map<String, List<BigDecimal>> samplesByMetric = new LinkedHashMap<>();
        for (HealthMetric metric : HealthMetric.values()) {
            samplesByMetric.put(metric.code(), new ArrayList<>());
        }
        // 按天聚合均值
        Map<LocalDate, List<HealthRecord>> byDate = new HashMap<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            byDate.put(d, new ArrayList<>());
        }
        for (HealthRecord record : records) {
            byDate.computeIfAbsent(record.getMeasuredAt().toLocalDate(), k -> new ArrayList<>())
                    .add(record);
            for (HealthMetric metric : HealthMetric.values()) {
                BigDecimal v = record.valueOf(metric.code());
                if (v != null) {
                    samplesByMetric.get(metric.code()).add(v);
                }
            }
        }

        return new HealthSummaryResponse(
                elderId,
                from,
                today,
                now.format(DATE_TIME),
                buildMetricSummaries(records, samplesByMetric),
                buildDailyBuckets(byDate, from, today));
    }

    private List<HealthSummaryResponse.MetricSummary> buildMetricSummaries(
            List<HealthRecord> records,
            Map<String, List<BigDecimal>> samplesByMetric) {

        // 命中次数按 metric 汇总（一条记录可能同一指标命中两条规则，如心率高+低）
        Map<String, Integer> hitCountByMetric = countHitsByMetric(records);

        List<HealthSummaryResponse.MetricSummary> summaries = new ArrayList<>();
        for (HealthMetric metric : HealthMetric.values()) {
            List<BigDecimal> values = samplesByMetric.get(metric.code());
            if (values.isEmpty()) {
                // 整周都没有该指标：各值均为 null，前端显示"暂无数据"
                summaries.add(new HealthSummaryResponse.MetricSummary(
                        metric.code(), metric.label(), metric.unit(), 0, null, null, null, 0));
                continue;
            }
            summaries.add(new HealthSummaryResponse.MetricSummary(
                    metric.code(),
                    metric.label(),
                    metric.unit(),
                    values.size(),
                    average(values),
                    max(values),
                    min(values),
                    hitCountByMetric.getOrDefault(metric.code(), 0)));
        }
        return summaries;
    }

    /**
     * 统计各指标的规则命中次数。
     *
     * <p>从已保存的命中明细读，而不是重新对全部记录跑一遍规则：
     * 规则可能已经改版，重算会得到与当时不同的结论，统计就会失真。
     */
    private Map<String, Integer> countHitsByMetric(List<HealthRecord> records) {
        Map<String, Integer> hitCount = new HashMap<>();
        for (HealthRecord record : records) {
            for (HealthRecordAnomaly anomaly : anomalyMapper.findByRecordId(record.getId())) {
                hitCount.merge(anomaly.getMetric(), 1, Integer::sum);
            }
        }
        return hitCount;
    }

    /**
     * 构建按天的分组。
     *
     * <p>当天某指标无样本时放入 null，<b>不补 0、不复制前值</b>——
     * 补 0 会画出虚假的零值线，复制前值会掩盖"这天根本没测"。
     */
    private List<HealthSummaryResponse.DayBucket> buildDailyBuckets(
            Map<LocalDate, List<HealthRecord>> byDate,
            LocalDate from,
            LocalDate today) {

        List<HealthSummaryResponse.DayBucket> buckets = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            List<HealthRecord> dayRecords = byDate.getOrDefault(d, List.of());
            Map<String, BigDecimal> averages = new LinkedHashMap<>();
            for (HealthMetric metric : HealthMetric.values()) {
                List<BigDecimal> values = new ArrayList<>();
                for (HealthRecord r : dayRecords) {
                    BigDecimal v = r.valueOf(metric.code());
                    if (v != null) {
                        values.add(v);
                    }
                }
                // 无样本时留 null，由前端显示空缺
                averages.put(metric.code(), values.isEmpty() ? null : average(values));
            }
            buckets.add(new HealthSummaryResponse.DayBucket(d, dayRecords.size(), averages));
        }
        return buckets;
    }

    /** 均值按全部原始样本计算；scale 依指标精度而定 */
    private BigDecimal average(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal max(List<BigDecimal> values) {
        return values.stream().max(BigDecimal::compareTo).orElse(null);
    }

    private BigDecimal min(List<BigDecimal> values) {
        return values.stream().min(BigDecimal::compareTo).orElse(null);
    }
}
