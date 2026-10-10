package org.imnu.smartcommunitycare.health.model.enums;

import org.imnu.smartcommunitycare.common.exception.BusinessException;

import java.math.BigDecimal;

/**
 * 体征异常判定规则。
 *
 * <p><b>阈值仅作演示配置，不代表医学标准。</b>界面用"规则命中""待核实事项"
 * 这类表述，不生成疾病结论、不推荐药物、不宣称诊断。
 *
 * <p>判定边界统一为：<b>低于下界或高于上界时命中，恰好等于界限不命中</b>。
 * 这样"上界=140"时140 属于正常，语义明确且可预期。
 */
public enum HealthRule {

    HEART_RATE_HIGH("HEART_RATE_HIGH", "心率高于演示阈值", HealthMetric.HEART_RATE, null, "100"),
    HEART_RATE_LOW("HEART_RATE_LOW", "心率低于演示阈值", HealthMetric.HEART_RATE, "60", null),
    SYSTOLIC_HIGH("SYSTOLIC_HIGH", "收缩压高于演示阈值", HealthMetric.SYSTOLIC, null, "140"),
    DIASTOLIC_HIGH("DIASTOLIC_HIGH", "舒张压高于演示阈值", HealthMetric.DIASTOLIC, null, "90"),
    BLOOD_SUGAR_HIGH("BLOOD_SUGAR_HIGH", "血糖高于演示阈值", HealthMetric.BLOOD_SUGAR, null, "7.0"),
    BODY_TEMPERATURE_HIGH("BODY_TEMPERATURE_HIGH", "体温高于演示阈值", HealthMetric.BODY_TEMPERATURE, null, "37.5"),
    SLEEP_TOO_LITTLE("SLEEP_TOO_LITTLE", "睡眠时长低于演示阈值", HealthMetric.SLEEP_HOURS, "6", null);

    private final String code;
    private final String description;
    private final HealthMetric metric;
    private final BigDecimal lowerBound;
    private final BigDecimal upperBound;

    HealthRule(String code, String description, HealthMetric metric, String lower, String upper) {
        this.code = code;
        this.description = description;
        this.metric = metric;
        this.lowerBound = lower == null ? null : new BigDecimal(lower);
        this.upperBound = upper == null ? null : new BigDecimal(upper);
        if (lowerBound != null && upperBound != null && lowerBound.compareTo(upperBound) >= 0) {
            throw new IllegalStateException("规则 " + code + " 的下界必须小于上界");
        }
    }

    public String code() {
        return code;
    }

    public String description() {
        return description;
    }

    public HealthMetric metric() {
        return metric;
    }

    public BigDecimal lowerBound() {
        return lowerBound;
    }

    public BigDecimal upperBound() {
        return upperBound;
    }

    public String unit() {
        return metric.unit();
    }

    /**
     * 判断某个实测值是否命中本规则。
     *
     * <p>缺测（value 为 null）不判断，直接返回 false —— 缺测不是"正常"，
     * 更不是"异常"，把它算作任一方都会让统计失真。
     *
     * @param value 实测值，可为 null
     * @return 低于下界或高于上界时为 true；等于界限为 false
     */
    public boolean matches(BigDecimal value) {
        if (value == null) {
            return false;
        }
        if (lowerBound != null && value.compareTo(lowerBound) < 0) {
            return true;
        }
        return upperBound != null && value.compareTo(upperBound) > 0;
    }

    /** 按编码查找规则，未知编码直接拒绝而不是忽略 */
    public static HealthRule fromCode(String code) {
        for (HealthRule rule : values()) {
            if (rule.code.equals(code)) {
                return rule;
            }
        }
        throw BusinessException.badRequest("未知的规则编码：" + code);
    }
}
