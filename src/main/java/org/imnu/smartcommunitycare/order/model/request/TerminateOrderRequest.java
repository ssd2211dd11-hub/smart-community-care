package org.imnu.smartcommunitycare.order.model.request;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 异常终止请求。原因必填——终止代表服务未能完成，
 * 事后必须能查清是谁核实后终止的、为什么。
 */
public record TerminateOrderRequest(

        @NotBlank(message = "请填写终止原因")
        @Size(max = 500, message = "终止原因长度不能超过 500")
        String reason,

        /** 页面读取到的订单版本；必填，旧版本返回 409。 */
        @NotNull(message = "请携带订单版本")
        @Min(value = 0, message = "订单版本不能小于 0")
        Integer version
) {
}
