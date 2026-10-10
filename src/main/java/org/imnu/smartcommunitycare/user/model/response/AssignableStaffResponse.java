package org.imnu.smartcommunitycare.user.model.response;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 分派对象选择项。
 *
 * <p><b>刻意只含 id、姓名、角色</b>：这是分派/转派动作真正需要的信息。
 * 不返回密码哈希、联系方式、健康资料等无关内容——
 * 页面不需要的字段，就不该从后端出去。
 */
public record AssignableStaffResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String realName,
        String role
) {
}
