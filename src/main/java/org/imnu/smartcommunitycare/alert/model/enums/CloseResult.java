package org.imnu.smartcommunitycare.alert.model.enums;

import org.imnu.smartcommunitycare.common.exception.BusinessException;

/**
 * 关闭结果。
 *
 * <p>没有"已处理/未处理"这种模糊说法：关闭必须说明是处理完了、
 * 还是当时判断为误报、还是发现数据本身录错了。
 */
public enum CloseResult {

    /** 已处理 */
    RESOLVED,

    /** 误报：当时规则命中但实际无需处理 */
    FALSE_ALARM,

    /** 数据纠错：事后发现是录入有误 */
    DATA_CORRECTION;

    public static CloseResult from(String value) {
        for (CloseResult r : values()) {
            if (r.name().equals(value)) {
                return r;
            }
        }
        throw BusinessException.badRequest("未知的关闭结果：" + value);
    }
}
