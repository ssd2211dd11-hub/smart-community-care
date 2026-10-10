package org.imnu.smartcommunitycare.order.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单详情安全响应，包含下单快照、当前责任人及履约状态。
 * 不返回请求键和摘要；ID使用字符串，避免JavaScript整数精度丢失。
 * @param id                     订单 ID（字符串）
 * @param orderNo                订单号
 * @param status                 当前状态（枚举名）
 * @param serviceNameSnapshot    服务名<b>快照</b>——下单时定格，不随目录改名而变
 * @param priceSnapshot          参考价格<b>快照</b>
 * @param slotStartSnapshot      预约开始时间快照
 * @param slotEndSnapshot        预约结束时间快照
 * @param contactName            联系人姓名快照
 * @param contactPhone           联系电话快照
 * @param addressSnapshot        地址快照
 * @param staffId                当前责任人 ID；<b>未分派时为 null</b>
 * @param staffName              当前责任人姓名；未分派时为 null
 * @param serviceResult          服务结果；未提交时为 null
 * @param startedAt              开工时间；未开工为 null
 * @param confirmedAt            确认时间；未确认为 null
 * @param cancelledAt            取消时间；未取消为 null
 * @param cancelReason           取消原因；未取消为 null
 * @param version                乐观锁版本，动作提交时应带回
 * @param createdAt              建单时间
 */
public record OrderDetailResponse(
        String id,
        String orderNo,
        String status,
        String serviceNameSnapshot,
        BigDecimal priceSnapshot,
        LocalDateTime slotStartSnapshot,
        LocalDateTime slotEndSnapshot,
        String contactName,
        String contactPhone,
        String addressSnapshot,
        String staffId,
        String staffName,
        String serviceResult,
        LocalDateTime startedAt,
        LocalDateTime confirmedAt,
        LocalDateTime cancelledAt,
        String cancelReason,
        Integer version,
        LocalDateTime createdAt
) {
}
