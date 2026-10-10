package org.imnu.smartcommunitycare.health.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 健康记录响应。
 *
 * <p>用 record 显式列出可返回字段，不直接返回实体——
 * 后续实体加内部字段时，响应不会跟着泄漏出去。
 */
public record HealthRecordResponse(
        @JsonSerialize(using=ToStringSerializer.class) Long id,
        @JsonSerialize(using=ToStringSerializer.class) Long elderId,
        Integer heartRate,
        BigDecimal systolic,
        BigDecimal diastolic,
        BigDecimal bloodSugar,
        BigDecimal bodyTemperature,
        BigDecimal sleepHours,
        String measureScene,
        LocalDateTime measuredAt,
        String mode,
        String source,
        Boolean voided,
        String voidReason,
        LocalDateTime voidedAt,
        List<AnomalyResponse> anomalies
) {

    /** 规则命中明细 */
    public record AnomalyResponse(
            String ruleCode,
            String ruleVersion,
            String metric,
            String metricLabel,
            BigDecimal measuredValue,
            BigDecimal lowerBound,
            BigDecimal upperBound,
            String unit,
            String description
    ) {
    }
}
