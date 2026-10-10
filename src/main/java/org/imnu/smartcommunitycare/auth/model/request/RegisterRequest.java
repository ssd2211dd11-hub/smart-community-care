package org.imnu.smartcommunitycare.auth.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 公开注册请求。
 *
 * <p><b>这个类里没有 role 字段——这是刻意的。</b>
 * 公开注册只能创建老人账号，若允许前端传role，任何人都能注册成管理员
 * （旧项目就是栽在这里：`UserServiceImpl.register` 直接采用前端传来的 role）。
 */
public record RegisterRequest(

        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{3,50}$",
                message = "用户名由 3-50 位字母、数字或下划线组成")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间")
        String password,

        @NotBlank(message = "姓名不能为空")
        @Size(max = 50, message = "姓名长度不能超过 50")
        String realName,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Email(message = "邮箱格式不正确")
        String email
) {
}
