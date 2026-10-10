package org.imnu.smartcommunitycare.auth.model.response;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 登录成功返回。
 *
 * <p>字段由后端确定后返回，前端不自行拼装。
 * 角色同时返回给前端仅用于<b>显示菜单</b>，权限判断以后端为准。
 */
public record LoginResponse(
        String token,
        @JsonSerialize(using = ToStringSerializer.class) Long userId,
        String username,
        String realName,
        String role
) {
}
