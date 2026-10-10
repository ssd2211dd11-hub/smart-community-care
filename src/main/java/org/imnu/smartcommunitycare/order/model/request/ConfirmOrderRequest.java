package org.imnu.smartcommunitycare.order.model.request;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.Size;

/**
 * 管理员代确认请求。
 *
 * <p>reason 在 DTO 层不做 @NotBlank：老人本人确认不需要原因，
 * 只有管理员代确认才需要。是否必填由 Service 按调用场景判断，
 * 避免同一个类被两处复用时注解互相干扰。
 */
public record ConfirmOrderRequest(

        @Size(max = 500, message = "说明长度不能超过 500")
        String reason,

        /** 页面读取到的订单版本；必填，旧版本返回 409。 */
        @NotNull(message = "请携带订单版本")
        @Min(value = 0, message = "订单版本不能小于 0")
        Integer version
) {
}
