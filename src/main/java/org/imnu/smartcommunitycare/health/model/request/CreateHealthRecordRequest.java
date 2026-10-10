package org.imnu.smartcommunitycare.health.model.request;

import org.imnu.smartcommunitycare.common.exception.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 健康记录录入请求。
 *
 * <p>校验放在请求对象自身的静态工厂里：录入规则是业务约束的一部分，
 * 散落到 Controller 或 Service 都会出现"这个字段忘了校验"的漏洞。
 */
public record CreateHealthRecordRequest(

        /* 管理员代录时必填；老人自己录入时忽略此字段，后端用会话里的档案 */
        Long elderId,

        Integer heartRate,
        BigDecimal systolic,
        BigDecimal diastolic,
        BigDecimal bloodSugar,
        BigDecimal bodyTemperature,
        BigDecimal sleepHours,

        String measureScene,
        String mode,
        LocalDateTime measuredAt,
        String requestId
) {

    /** 至少要有一项指标；全空的记录没有意义 */
    public void requireAtLeastOneMetric() {
        if (isAllAbsent()) {
            throw BusinessException.badRequest("请至少填写一项健康指标");
        }
    }

    /**
     * 血压必须成对出现。
     *
     * <p>只填收缩压不填舒张压，会让报告里出现"平均血压 125.0/null"这种读不懂的值。
     */
    public void requireBloodPressurePair() {
        if ((systolic == null) != (diastolic == null)) {
            throw BusinessException.badRequest("血压需成对填写：收缩压与舒张压必须同时提供");
        }
        if (systolic != null && systolic.compareTo(diastolic) <= 0) {
            throw BusinessException.badRequest("收缩压应大于舒张压");
        }
    }

    /** 各项指标的合法范围与小数位 */
    public void requireValidRanges() {
        if (heartRate != null && (heartRate <= 0 || heartRate > 300)) {
            throw BusinessException.badRequest("心率需为1-300 之间的正整数");
        }
        if (systolic != null && (systolic.compareTo(BigDecimal.ZERO) <= 0 || systolic.compareTo(new BigDecimal("300")) > 0)) {
            throw BusinessException.badRequest("收缩压需在 0-300 mmHg 之间");
        }
        if (diastolic != null && (diastolic.compareTo(BigDecimal.ZERO) <= 0 || diastolic.compareTo(new BigDecimal("200")) > 0)) {
            throw BusinessException.badRequest("舒张压需在 0-200 mmHg 之间");
        }
        if (bloodSugar != null && (bloodSugar.compareTo(BigDecimal.ZERO) <= 0 || bloodSugar.compareTo(new BigDecimal("50")) > 0)) {
            throw BusinessException.badRequest("血糖需在 0-50 mmol/L 之间");
        }
        if (bodyTemperature != null && (bodyTemperature.compareTo(new BigDecimal("30")) < 0
                || bodyTemperature.compareTo(new BigDecimal("45")) > 0)) {
            throw BusinessException.badRequest("体温需在 30-45 ℃ 之间");
        }
        if (sleepHours != null && (sleepHours.compareTo(BigDecimal.ZERO) < 0
                || sleepHours.compareTo(new BigDecimal("24")) > 0)) {
            throw BusinessException.badRequest("睡眠时长需在 0-24 小时之间");
        }
    }

    /** 精度：体温与血压一位小数，血糖两位，睡眠一位 */
    public void requireValidPrecision() {
        requireScale(systolic, 1, "收缩压");
        requireScale(diastolic, 1, "舒张压");
        requireScale(bloodSugar, 2, "血糖");
        requireScale(bodyTemperature, 1, "体温");
        requireScale(sleepHours, 1, "睡眠时长");
    }

    /** 未知枚举明确拒绝，不静默当默认值 */
    public String resolveScene() {
        if (measureScene == null || measureScene.isBlank()) {
            return "UNKNOWN";
        }
        if (!List.of("FASTING", "POSTPRANDIAL", "RANDOM", "UNKNOWN").contains(measureScene)) {
            throw BusinessException.badRequest("未知的测量场景：" + measureScene);
        }
        return measureScene;
    }

    private boolean isAllAbsent() {
        return heartRate == null && systolic == null && diastolic == null
                && bloodSugar == null && bodyTemperature == null && sleepHours == null;
    }

    private void requireScale(BigDecimal value, int scale, String label) {
        if (value == null) {
            return;
        }
        if (value.stripTrailingZeros().scale() > scale) {
            throw BusinessException.badRequest(label + "最多保留 " + scale + " 位小数");
        }
    }

    /** 归一化数值，避免 36.50 与 36.5 被当成不同内容参与防重摘要 */
    public static BigDecimal normalize(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }
}
