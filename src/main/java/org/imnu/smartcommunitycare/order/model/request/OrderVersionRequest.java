package org.imnu.smartcommunitycare.order.model.request;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.NotNull;

/**
 * 动作携带页面读取到的订单版本。
 * 版本缺失时拒绝请求，版本过期返回409，不产生状态、名额或日志变更。
 * @param version 页面读取到的订单版本，必填；缺失不允许绕过竞争检查。
 */
public record OrderVersionRequest(

        @NotNull(message = "请携带订单版本")
        @Min(value = 0, message = "订单版本不能小于 0")
        Integer version
) {
}
