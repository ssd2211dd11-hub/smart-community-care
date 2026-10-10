package org.imnu.smartcommunitycare.order.model.request;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 服务结果提交请求。
 *
 * <p>同时用于「提交服务结果」与「反馈未完成事项」：
 * 两者都要求必填一段说明，语义不同但结构一致。
 */
public record SubmitResultRequest(

        @NotBlank(message = "请填写服务结果")
        @Size(max = 500, message = "服务结果长度不能超过 500")
        String result,

        /** 页面读取到的订单版本；必填，旧版本返回 409。 */
        @NotNull(message = "请携带订单版本")
        @Min(value = 0, message = "订单版本不能小于 0")
        Integer version
) {
}
