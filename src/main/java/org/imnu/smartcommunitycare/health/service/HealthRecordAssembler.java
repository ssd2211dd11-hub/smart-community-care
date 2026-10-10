package org.imnu.smartcommunitycare.health.service;

import org.imnu.smartcommunitycare.health.mapper.HealthRecordAnomalyMapper;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecord;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecordAnomaly;
import org.imnu.smartcommunitycare.health.model.enums.HealthMetric;
import org.imnu.smartcommunitycare.health.model.enums.HealthRule;
import org.imnu.smartcommunitycare.health.model.response.HealthRecordResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 健康记录实体转响应。
 *
 * <p>单独成组件而不是塞进 Service：转换无状态、可单测，
 * 混在业务里会让 Service 越写越长。
 */
@Component
@RequiredArgsConstructor
public class HealthRecordAssembler {

    private final HealthRecordAnomalyMapper anomalyMapper;

    /** 转单条，并带上该记录的规则命中明细 */
    public HealthRecordResponse toResponse(HealthRecord record) {
        if (record == null) {
            return null;
        }
        return new HealthRecordResponse(
                record.getId(),
                record.getElderId(),
                record.getHeartRate(),
                record.getSystolic(),
                record.getDiastolic(),
                record.getBloodSugar(),
                record.getBodyTemperature(),
                record.getSleepHours(),
                record.getMeasureScene(),
                record.getMeasuredAt(),
                record.getMode(),
                record.getSource(),
                record.getVoided(),
                record.getVoidReason(),
                record.getVoidedAt(),
                toAnomalyResponses(record.getId()));
    }

    public List<HealthRecordResponse> toResponses(List<HealthRecord> records) {
        return records.stream().map(this::toResponse).toList();
    }

    private List<HealthRecordResponse.AnomalyResponse> toAnomalyResponses(Long recordId) {
        return anomalyMapper.findByRecordId(recordId).stream()
                .map(this::toAnomalyResponse)
                .toList();
    }

    /**
     * 命中明细转响应。
     *
     * <p>指标与规则的展示名查不到时回退为原始编码，而不是抛异常——
     * 一条规则的编码改动不应让整条健康记录打不开。
     */
    private HealthRecordResponse.AnomalyResponse toAnomalyResponse(HealthRecordAnomaly anomaly) {
        return new HealthRecordResponse.AnomalyResponse(
                anomaly.getRuleCode(),
                anomaly.getRuleVersion(),
                anomaly.getMetric(),
                labelOfMetric(anomaly.getMetric()),
                anomaly.getMeasuredValue(),
                anomaly.getLowerBound(),
                anomaly.getUpperBound(),
                anomaly.getUnit(),
                descriptionOfRule(anomaly.getRuleCode()));
    }

    private String labelOfMetric(String metricCode) {
        for (HealthMetric metric : HealthMetric.values()) {
            if (metric.code().equals(metricCode)) {
                return metric.label();
            }
        }
        return metricCode;
    }

    private String descriptionOfRule(String ruleCode) {
        if (ruleCode == null) {
            return null;
        }
        try {
            return HealthRule.fromCode(ruleCode).description();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
