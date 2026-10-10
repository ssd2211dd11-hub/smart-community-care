package org.imnu.smartcommunitycare.device.model.enums;

/**
 * 设备开关状态。
 *
 * <p>与「在线状态」严格分开：{@code last_seen_at} 判断设备是否还在通信，
 * 本枚举只表示开关本身的开或关。早期版本把 online/active 混在同一字段里，
 * 导致「显示在线但已关闭」这类自相矛盾的状态。
 */
public enum PowerStatus {
    ON,
    OFF
}
