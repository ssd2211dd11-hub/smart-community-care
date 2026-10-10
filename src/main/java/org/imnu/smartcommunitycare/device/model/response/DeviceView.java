package org.imnu.smartcommunitycare.device.model.response;

import org.imnu.smartcommunitycare.device.model.entity.Device;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.time.LocalDateTime;

/**
 * 设备视图。
 *
 * <p>与实体分开的原因只有两个：
 * <ul>
 *   <li>{@code onlineStatus} 是<b>计算值</b>，库里没有这一列，
 *       靠「最近心跳 vs 离线阈值」实时得出；</li>
 *   <li>{@code controlable} 是把「能否下发指令」的多条判断结果合成一个布尔，
 *       让页面不必自己复制业务规则——规则属于后端。</li>
 * </ul>
 */
public record DeviceView(

        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String deviceNo,
        String deviceName,
        String deviceType,
        @JsonSerialize(using = ToStringSerializer.class) Long elderId,
        /** 归属老人姓名，页面直接显示，避免再查一次档案 */
        String elderName,
        String accessMode,
        String location,
        Boolean enabled,
        Boolean simulatorRunning,
        String simulationMode,
        /** 虚拟设备自身当前状态 */
        String simulatedPowerStatus,
        /** 最近一次心跳上报的状态，首个心跳前为空 */
        String powerStatus,
        LocalDateTime lastSeenAt,
        /** ONLINE / OFFLINE / DISABLED */
        String onlineStatus,
        /** 当前是否可下发控制指令 */
        Boolean controlable,
        /** 不能下发时的原因，用于页面提示，前端不需要自己判断 */
        String controlBlockedReason,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static DeviceView from(Device device, String onlineStatus,
                                  boolean controlable, String blockedReason,
                                  String elderName) {
        return new DeviceView(
                device.getId(),
                device.getDeviceNo(),
                device.getDeviceName(),
                device.getDeviceType(),
                device.getElderId(),
                elderName,
                device.getAccessMode(),
                device.getLocation(),
                device.getEnabled(),
                device.getSimulatorRunning(),
                device.getSimulationMode(),
                device.getSimulatedPowerStatus(),
                device.getPowerStatus(),
                device.getLastSeenAt(),
                onlineStatus,
                controlable,
                blockedReason,
                device.getVersion(),
                device.getCreatedAt(),
                device.getUpdatedAt());
    }
}
