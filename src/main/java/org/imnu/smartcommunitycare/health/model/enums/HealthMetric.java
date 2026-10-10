package org.imnu.smartcommunitycare.health.model.enums;

/**
 * 体征指标。每个指标有自己的单位与上下界校验规则。
 */
public enum HealthMetric {

    HEART_RATE("heartRate", "心率", "bpm"),
    SYSTOLIC("systolic", "收缩压", "mmHg"),
    DIASTOLIC("diastolic", "舒张压", "mmHg"),
    BLOOD_SUGAR("bloodSugar", "血糖", "mmol/L"),
    BODY_TEMPERATURE("bodyTemperature", "体温", "℃"),
    SLEEP_HOURS("sleepHours", "睡眠时长", "h");

    private final String code;
    private final String label;
    private final String unit;

    HealthMetric(String code, String label, String unit) {
        this.code = code;
        this.label = label;
        this.unit = unit;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String unit() {
        return unit;
    }
}
