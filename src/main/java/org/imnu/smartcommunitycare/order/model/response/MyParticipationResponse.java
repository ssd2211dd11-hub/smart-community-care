package org.imnu.smartcommunitycare.order.model.response;

import java.time.LocalDateTime;

/**
 * 本人参与历史的只读摘要，按操作日志保留参与事实，转派后仍可查询。
 * 只包含服务快照、必要的老人姓名及本人动作，不返回电话、地址、请求键或完整健康记录。
 * @param orderId           订单 ID（字符串，避免 JS 精度丢失）
 * @param orderNo           订单号
 * @param serviceNameSnapshot 服务名快照（下单当时）
 * @param slotStartSnapshot 预约开始时间快照
 * @param slotEndSnapshot   预约结束时间快照
 * @param elderName         老人姓名（最小必要信息）
 * @param myActions         本人在该订单上执行过的动作（如 接单 / 开工 / 提交结果）
 * @param lastActionAt      本人最后一次动作的时间
 * @param finalStatus       订单当前状态（用于说明后来如何了）
 */
public record MyParticipationResponse(
        String orderId,
        String orderNo,
        String serviceNameSnapshot,
        LocalDateTime slotStartSnapshot,
        LocalDateTime slotEndSnapshot,
        String elderName,
        String myActions,
        LocalDateTime lastActionAt,
        String finalStatus
) {
}
