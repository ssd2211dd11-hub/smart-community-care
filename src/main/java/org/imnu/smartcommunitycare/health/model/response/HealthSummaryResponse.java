package org.imnu.smartcommunitycare.health.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 七日健康概览。
 *
 * <p>统计口径（实现与文档必须一致，改动时同步此处）：
 * <ul>
 *   <li>范围为<b>今天及前 6 个自然日</b>，按服务器本地时区，不是"最近 7 条"</li>
 *   <li>均值按<b>全部有效原始样本</b>计算，不是"每日均值再平均"——
 *       每天样本数不同，两种算法结果不同，必须说清用的是哪种</li>
 *   <li>某项指标当日无有效样本时，该日返回 {@code null}，<b>不补0、不复制前值</b>；
 *       补0会画出一条虚假的直线，复制前值会掩盖"这天没测"</li>
 *   <li>缺测指标不参与任何统计与规则判定</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record HealthSummaryResponse(
        Long elderId,
        LocalDate fromDate,
        LocalDate toDate,
        /** 服务端计算时刻，页面据此显示"数据截至" */
        String dataAsOf,
        List<MetricSummary> metrics,
        List<DayBucket> daily
) {

    /**
     * 单项指标的七日汇总。
     *
     * @param metric        指标编码
     * @param label         指标中文名
     * @param unit          单位
     * @param sampleCount   七天内有效样本总数
     * @param average       均值，无样本时为 null
     * @param max           最大值，无样本时为 null
     * @param min           最小值，无样本时为 null
     * @param hitCount      规则命中总次数
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MetricSummary(
            String metric,
            String label,
            String unit,
            int sampleCount,
            BigDecimal average,
            BigDecimal max,
            BigDecimal min,
            int hitCount
    ) {
    }

    /**
     * 某一天的分组数据，供趋势图使用。
     *
     * @param date     自然日
     * @param sampleCount 当天有效样本数
     * @param values   当天各指标均值；当天没测的指标为 null（不补 0）
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DayBucket(
            LocalDate date,
            int sampleCount,
            Map<String, BigDecimal> averages
    ) {
    }
}
