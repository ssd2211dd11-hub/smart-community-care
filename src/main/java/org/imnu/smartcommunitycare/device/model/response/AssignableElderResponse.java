package org.imnu.smartcommunitycare.device.model.response;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 设备归属老人选择项。
 *
 * <p><b>只含档案 ID 与姓名</b>：这是选择归属时真正需要的信息。
 * 老人档案里还有出生日期、住址、紧急联系人、健康备注，
 * 建一台电灯不需要知道这些，就不该从这里出去。
 *
 * <p>{@code id} 是 {@code elder_profile.id}，不是账号 ID——
 * device.elder_id 存的就是档案主键，返回错一个会让建档时的归属
 * 指向一条不存在的记录。
 */
public record AssignableElderResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String realName
) {
}
