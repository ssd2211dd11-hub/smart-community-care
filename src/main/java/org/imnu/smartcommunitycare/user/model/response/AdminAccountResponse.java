package org.imnu.smartcommunitycare.user.model.response;
import java.time.LocalDateTime;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
public record AdminAccountResponse(@JsonSerialize(using=ToStringSerializer.class) Long id,String username,String realName,String phone,String email,
                                   String role,Boolean enabled,LocalDateTime createdAt) {
    public static AdminAccountResponse from(SysUser user) {
        return new AdminAccountResponse(user.getId(),user.getUsername(),user.getRealName(),user.getPhone(),
                user.getEmail(),user.getRole(),user.getEnabled(),user.getCreatedAt());
    }
}
