package org.imnu.smartcommunitycare.order.model.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 待领取池只提供服务摘要；领取前不泄露老人身份、电话、地址与请求键。 */
public record ClaimableOrderResponse(String id, String orderNo, String serviceNameSnapshot,
        BigDecimal priceSnapshot, LocalDateTime slotStartSnapshot, LocalDateTime slotEndSnapshot,
        Integer version) { }
