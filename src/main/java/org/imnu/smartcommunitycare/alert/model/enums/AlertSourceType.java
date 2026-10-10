package org.imnu.smartcommunitycare.alert.model.enums;

import org.imnu.smartcommunitycare.common.exception.BusinessException;

/**
 * 事件来源。
 *
 * <p>两类来源共用一套处理流程，但<b>去重方式不同</b>：
 * 健康告警按「同一老人 + 同一规则」归并成一条未结事件；
 * 主动求助只按 requestId 防重，同一老人短时间内再次求助是两次独立事件。
 */
public enum AlertSourceType {

    /** 来自健康记录的规则命中 */
    HEALTH_ALERT,

    /** 老人主动提交 */
    HELP_CALL;

    public static AlertSourceType from(String value) {
        for (AlertSourceType t : values()) {
            if (t.name().equals(value)) {
                return t;
            }
        }
        throw BusinessException.badRequest("未知的事件来源：" + value);
    }
}
