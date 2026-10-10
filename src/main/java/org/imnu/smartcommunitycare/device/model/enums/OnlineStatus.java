package org.imnu.smartcommunitycare.device.model.enums;

/**
 * 设备在线状态（计算值，不持久化）。
 *
 * <p>由最近一次有效心跳与离线阈值比较得出，读取列表时实时计算即可，
 * 不需要定时任务去刷每台设备的在线字段。
 */
public enum OnlineStatus {
    /** 启用且最近有有效心跳 */
    ONLINE,
    /** 启用但超过离线阈值未收到心跳 */
    OFFLINE,
    /** 已停用或模拟器未启动 */
    DISABLED
}
