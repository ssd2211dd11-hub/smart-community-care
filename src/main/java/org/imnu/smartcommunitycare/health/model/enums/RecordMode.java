package org.imnu.smartcommunitycare.health.model.enums;

import org.imnu.smartcommunitycare.common.exception.BusinessException;

import java.time.LocalDateTime;

/**
 * 录入模式。
 *
 * <p>区分"当前测量"与"历史补录"是硬要求：让用户能把补录的旧数据
 * 当成实时数据，才会造成"明明三天前测的血压却触发了紧急告警"的误报。
 */
public enum RecordMode {

    /** 当前测量：测量时间取后端保存时刻，命中规则会进入待办 */
    CURRENT,

    /** 历史补录：测量时间由用户填写（不晚于当前），只影响历史展示，不产生当前告警 */
    HISTORY;

    public static RecordMode from(String value) {
        if (value == null || value.isBlank()) {
            return CURRENT;
        }
        for (RecordMode mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        throw BusinessException.badRequest("未知的录入模式：" + value);
    }

    /**
     * 解析本次录入的测量时间。
     *
     * @param mode        录入模式
     * @param userTypedAt 用户填写的测量时间，仅 HISTORY 模式有效
     * @param now         后端当前时间
     * @return 实际使用的测量时间
     */
    public LocalDateTime resolveMeasuredAt(LocalDateTime userTypedAt, LocalDateTime now) {
        if (this == CURRENT) {
            // 当前测量不接受自定义时间：用户若想指定过去的时间，应改用历史补录
            return now;
        }
        if (userTypedAt == null) {
            throw BusinessException.badRequest("历史补录必须填写测量时间");
        }
        if (userTypedAt.isAfter(now)) {
            throw BusinessException.badRequest("测量时间不能晚于当前时间");
        }
        return userTypedAt;
    }
}
