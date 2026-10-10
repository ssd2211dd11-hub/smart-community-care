package org.imnu.smartcommunitycare.auth.controller;

import org.imnu.smartcommunitycare.auth.model.request.LoginRequest;
import org.imnu.smartcommunitycare.auth.model.request.RegisterRequest;
import org.imnu.smartcommunitycare.auth.model.response.LoginResponse;
import org.imnu.smartcommunitycare.auth.service.AuthService;
import org.imnu.smartcommunitycare.common.api.ApiResponse;
import org.imnu.smartcommunitycare.user.model.response.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>薄 Controller：只做参数绑定、基础校验、调用服务、包装响应。
 * 业务判断全在 Service 里。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 登录 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success("登录成功", authService.login(request));
    }

    /** 公开注册，固定创建老人账号 */
    @PostMapping("/register")
    public ApiResponse<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ApiResponse.success("注册成功");
    }

    /** 退出登录，令牌立即失效 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.success("已退出登录");
    }

    /** 当前登录账号信息。前端刷新后先调它拿角色事实，不信任本地存的 role */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        return ApiResponse.success("获取成功", authService.currentUser());
    }
}
