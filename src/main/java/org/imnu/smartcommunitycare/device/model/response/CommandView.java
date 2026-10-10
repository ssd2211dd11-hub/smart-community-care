package org.imnu.smartcommunitycare.device.model.response;

import org.imnu.smartcommunitycare.device.model.entity.DeviceCommand;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 控制指令视图。
 *
 * <p>{@code status} 是「有效状态」而非库里的原值：调度可能因故延迟落库过期状态，
 * 读取时若发现已过期待执行却仍是 PENDING，这里按 TIMED_OUT 展示，
 * 页面就不会继续把它显示成可执行指令。<b>该计算不修改数据库。</b>
 */
public record CommandView(

        @JsonSerialize(using = ToStringSerializer.class) Long id,
        @JsonSerialize(using = ToStringSerializer.class) Long deviceId,
        String deviceName,
        /** 首版固定 SET_POWER */
        String action,
        String targetPowerStatus,
        /** PENDING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED */
        String status,
        String resultPowerStatus,
        /** 失败或取消原因；TIMED_OUT 无原因，因为「结果未知」本身就是原因 */
        String reason,
        LocalDateTime readyAt,
        LocalDateTime expiresAt,
        LocalDateTime executedAt,
        LocalDateTime acknowledgedAt,
        LocalDateTime createdAt
) {

    public static CommandView from(DeviceCommand c, String effectiveStatus,
                                   String deviceName) {
        return new CommandView(
                c.getId(),
                c.getDeviceId(),
                deviceName,
                c.getAction(),
                c.getTargetPowerStatus(),
                effectiveStatus,
                c.getResultPowerStatus(),
                c.getReason(),
                c.getReadyAt(),
                c.getExpiresAt(),
                c.getSimulatedExecutedAt(),
                c.getAcknowledgedAt(),
                c.getCreatedAt());
    }
}
