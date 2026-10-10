package org.imnu.smartcommunitycare.user.controller;

import org.imnu.smartcommunitycare.common.api.ApiResponse;
import org.imnu.smartcommunitycare.user.model.request.ChangePasswordRequest;
import org.imnu.smartcommunitycare.user.model.request.UpdateMyProfileRequest;
import org.imnu.smartcommunitycare.user.model.response.AssignableStaffResponse;
import org.imnu.smartcommunitycare.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 账号维护接口。
 *
 * <p>与 {@code AuthController} 分开：登录注册是入口，账号维护是管理动作。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final org.imnu.smartcommunitycare.user.service.UserManagementService managementService;

    @GetMapping("/users/paged")
    public ApiResponse<?> accounts(org.imnu.smartcommunitycare.user.model.request.AdminAccountQuery query) {
        return ApiResponse.success("查询成功",managementService.listAccounts(query));
    }

    @PostMapping("/users")
    public ApiResponse<Void> createAccount(@Valid @RequestBody org.imnu.smartcommunitycare.user.model.request.CreateAccountRequest request) {
        managementService.createAccount(request);return ApiResponse.success("账号已创建");
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<?> account(@PathVariable Long userId) {
        return ApiResponse.success("查询成功",managementService.detail(userId));
    }

    @PutMapping("/users/{userId}/profile")
    public ApiResponse<Void> updateAccount(@PathVariable Long userId,@Valid @RequestBody UpdateMyProfileRequest request) {
        managementService.updateAccount(userId,request);return ApiResponse.success("账号资料已更新");
    }

    @PutMapping("/users/{userId}/enable")
    public ApiResponse<Void> enable(@PathVariable Long userId) {
        managementService.enableAccount(userId);return ApiResponse.success("账号已启用");
    }

    /**
     * 可分派的服务人员列表（管理员）。
     *
     * <p>供分派/转派弹窗选择目标人员。只返回 id、姓名、角色，
     * 不含密码哈希、联系方式与健康资料。
     */
    @GetMapping("/users/assignable-staff")
    public ApiResponse<List<AssignableStaffResponse>> assignableStaff() {
        return ApiResponse.success("查询成功", userService.listAssignableStaff());
    }

    /**
     * 修改本人密码。
     *
     * <p>需登录；改密成功后旧令牌立即失效，需用新密码重新登录。
     */
    @PutMapping("/auth/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changeOwnPassword(request);
        return ApiResponse.success("密码已修改，请重新登录");
    }

    /**
     * 更新当前会话用户的资料；请求不接收用户ID、角色、启用状态或账号名。
     */
    @PutMapping("/auth/profile")
    public ApiResponse<Void> updateMyProfile(@Valid @RequestBody UpdateMyProfileRequest request) {
        userService.updateMyProfile(request);
        return ApiResponse.success("资料已更新");
    }

    /** 停用账号（仅管理员） */
    @PutMapping("/users/{userId}/disable")
    public ApiResponse<Void> disableAccount(@PathVariable Long userId) {
        userService.disableAccount(userId);
        return ApiResponse.success("账号已停用");
    }
}
