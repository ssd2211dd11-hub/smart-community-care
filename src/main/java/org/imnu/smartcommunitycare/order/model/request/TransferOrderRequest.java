package org.imnu.smartcommunitycare.order.model.request;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 转派请求。
 *
 * <p>与分派分开成独立 DTO，而不是共用一个带多个可空字段的类：
 * 两者都必填的原因字段如果在同一个类里放成可选，忘记校验就会
 * 出现「转派没有原因」的脏数据。分开后注解即可表达清楚。
 */
public record TransferOrderRequest(

        @NotNull(message = "请选择新的服务人员")
        Long newStaffId,

        @NotBlank(message = "请填写转派原因")
        @Size(max = 500, message = "转派原因长度不能超过 500")
        String reason,

        /** 页面读取到的订单版本；必填，旧版本返回 409。 */
        @NotNull(message = "请携带订单版本")
        @Min(value = 0, message = "订单版本不能小于 0")
        Integer version
) {
}
