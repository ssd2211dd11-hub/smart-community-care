package org.imnu.smartcommunitycare.alert.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 老人主动提交求助。
 *
 * <p>requestId 必填且由前端为"一次提交"生成：网络重试沿用同一个值，
 * 用户重新发起才换新的。这样既能防重，又不会把两次真实求助吞成一次。
 */
public record CreateHelpCallRequest(

        @NotBlank(message = "请选择求助事项类型")
        @Size(max = 50, message = "事项类型过长")
        String callType,

        @NotBlank(message = "请填写联系电话")
        @Size(max = 20, message = "联系电话过长")
        String contactPhone,

        @Size(max = 500, message = "说明不能超过 500 字")
        String description,

        @NotBlank(message = "缺少请求标识")
        @Size(max = 64, message = "请求标识过长")
        String requestId
) {
}
