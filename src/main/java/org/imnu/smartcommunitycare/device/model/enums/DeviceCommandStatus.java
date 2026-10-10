package org.imnu.smartcommunitycare.device.model.enums;

import org.imnu.smartcommunitycare.common.exception.BusinessException;

/**
 * 模拟控制指令状态。
 *
 * <p>关键：{@code TIMED_OUT} 表示<b>结果未知</b>，不是失败。
 * 设备可能已执行只是回执丢了，也可能根本没执行，两种情况无法区分，
 * 因此不写成 FAILED，页面也不得显示「设备一定未开启」。
 */
public enum DeviceCommandStatus {

    /** 已保存，尚无有效执行结果 */
    PENDING,

    /** 模拟器在期限内确认已达到目标状态 */
    SUCCEEDED,

    /** 模拟器明确报告执行失败 */
    FAILED,

    /** 到期未得到有效结果，执行结果未知 */
    TIMED_OUT,

    /** 设备停用或归属变更使待执行指令失效 */
    CANCELLED;

    public boolean isFinal() {
        return this != PENDING;
    }

    /**
     * 查询时统一按「已过期即 TIMED_OUT」展示。
     *
     * <p>调度可能因故延迟落库过期状态，读取时不能因此把过期指令
     * 继续显示成可执行的 PENDING，也不修改数据库。
     */
    public DeviceCommandStatus effectiveAt(java.time.LocalDateTime now,
                                          java.time.LocalDateTime expiresAt) {
        if (this == PENDING && expiresAt != null && !now.isBefore(expiresAt)) {
            return TIMED_OUT;
        }
        return this;
    }

    public static DeviceCommandStatus from(String value) {
        for (DeviceCommandStatus s : values()) {
            if (s.name().equals(value)) {
                return s;
            }
        }
        throw BusinessException.badRequest("未知的指令状态：" + value);
    }
}
