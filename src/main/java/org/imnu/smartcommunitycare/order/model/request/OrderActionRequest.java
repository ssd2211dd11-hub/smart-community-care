package org.imnu.smartcommunitycare.order.model.request;

import jakarta.validation.constraints.Min;

import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 订单业务动作的通用请求。
 *
 * <p>用于分派、接单、拒接、转派、开始服务、提交结果、确认、退回、取消、终止。
 * 这些动作的共同点是：<b>都需要一个原因或说明</b>，且都不接受客户端指定目标状态——
 * 目标状态由业务动作决定，不由前端传。
 *
 * <p>「提交结果」与「退回」需要填写服务内容或退回原因，落在 reason 字段。
 */
public record OrderActionRequest(

        @NotBlank(message = "请填写原因或说明")
        @Size(max = 500, message = "说明长度不能超过 500")
        String reason,

        /** 页面读取到的订单版本；必填，旧版本返回 409。 */
        @NotNull(message = "请携带订单版本")
        @Min(value = 0, message = "订单版本不能小于 0")
        Integer version
) {
}
