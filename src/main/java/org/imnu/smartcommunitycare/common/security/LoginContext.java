package org.imnu.smartcommunitycare.common.security;

import cn.dev33.satoken.stp.StpUtil;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;

/**
 * 当前登录账号上下文。
 *
 * <p>业务代码通过这里拿"是谁在操作"，<b>不信任前端传来的 userId 或 role</b>。
 * 操作者身份一律从已校验的会话中取。
 */
public final class LoginContext {

    private LoginContext() {
    }

    /** 是否已登录 */
    public static boolean isLoggedIn() {
        return StpUtil.isLogin();
    }

    /**
     * 当前登录账号 ID。
     *
     * @throws BusinessException 401 未登录
     */
    public static Long requireUserId() {
        if (!StpUtil.isLogin()) {
            throw BusinessException.unauthorized("未登录或登录已失效");
        }
        return Long.valueOf(StpUtil.getLoginIdAsString());
    }

    /**
     * 当前登录账号的角色。
     *
     * <p>角色从会话中取，不从客户端 localStorage 取——前端存的 role 只是展示用，
     * 任何人都能改。
     */
    public static UserRole requireRole() {
        Object role = StpUtil.getSession().get("role");
        if (role == null) {
            throw BusinessException.unauthorized("会话缺少角色信息，请重新登录");
        }
        return UserRole.from(role.toString());
    }

    /**
     * 校验当前角色是否在允许集合内，不在则 403。
     *
     * @param allowed 允许的角色
     */
    public static void requireAnyRole(UserRole... allowed) {
        UserRole current = requireRole();
        for (UserRole role : allowed) {
            if (current == role) {
                return;
            }
        }
        throw BusinessException.forbidden("当前角色无权执行此操作");
    }

    public static boolean isAdmin() {
        return requireRole() == UserRole.COMMUNITY_ADMIN;
    }
}
