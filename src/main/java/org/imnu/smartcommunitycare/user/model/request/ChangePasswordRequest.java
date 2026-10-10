package org.imnu.smartcommunitycare.user.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改本人密码请求。
 *
 * <p>必须提供原密码：没有原密码校验，拿到别人会话的人就能直接改密码。
 * 这个接口与"修改个人资料"分开，资料更新不触碰密码字段。
 */
public record ChangePasswordRequest(

        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "新密码长度需在 6-64 位之间")
        String newPassword
) {
}
