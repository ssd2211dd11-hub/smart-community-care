package org.imnu.smartcommunitycare.order.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建服务预约请求。
 *
 * <p><b>刻意没有 amount / price 字段</b>：金额由后端按服务当前价格快照，
 * 不接受客户端提交——否则可以伪造报价。也不接受 userId 与 status，
 * 归属取自会话、状态由后端决定。
 */
public record CreateOrderRequest(

        @NotNull(message = "请选择预约时段")
        Long slotId,

        @NotBlank(message = "请填写联系人姓名")
        @Size(max = 50, message = "联系人姓名长度不能超过 50")
        String contactName,

        @NotBlank(message = "请填写联系电话")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "联系电话格式不正确")
        String contactPhone,

        @Size(max = 255, message = "地址长度不能超过 255")
        String address,

        @NotBlank(message = "缺少请求标识")
        @Size(max = 64, message = "请求标识长度不能超过 64")
        String requestId
) {
}
