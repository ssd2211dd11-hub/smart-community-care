package org.imnu.smartcommunitycare.user.model.response;

import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 账号安全响应，不输出密码散列。
 * 老人档案字段仅在老人角色下返回，个人中心不提供工作人员填写的照护备注。
 */
public record UserResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String username,
        String realName,
        String phone,
        String email,
        UserRole role,
        Boolean enabled,
        String remark,

        // ---- 以下仅老人角色有值，其余角色为 null ----

        /** 出生日期 yyyy-MM-dd */
        String birthDate,
        /** 性别 */
        String gender,
        /** 住址 */
        String address,
        /** 紧急联系人姓名（仅档案字段，不建账号、不发通知） */
        String emergencyContact,
        /** 紧急联系人电话 */
        String emergencyPhone
) {
}
