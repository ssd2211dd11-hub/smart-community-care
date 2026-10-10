package org.imnu.smartcommunitycare.user.model.enums;

import org.imnu.smartcommunitycare.common.exception.BusinessException;

/**
 * 角色枚举。
 *
 * <p>一个账号只有一个角色。数据库存枚举的 name 值，不用 ordinal()——
 * ordinal 会随枚举顺序变化，存进去就变成脏数据。
 */
public enum UserRole {

    /** 社区管理员：全部老人档案、事件、订单、服务、设备 */
    COMMUNITY_ADMIN,

    /** 老人用户：仅本人档案、健康记录、求助、预约、评价 */
    ELDER,

    /** 服务人员：仅分派给自己的任务与订单 */
    SERVICE_STAFF;

    /**
     * 解析数据库存的字符串。
     *
     * <p>遇到未知角色抛 {@link BusinessException}（403）而不是
     * {@link IllegalArgumentException}：角色配置异常是"这个账号不能按任何角色使用"，
     * 属于权限问题；抛运行时异常会被兜底 handler 变成 500，
     * 让客户端误以为服务器故障而盲目重试。
     *
     * <p>同样不能返回 null 或降级成某个默认角色——旧库存在 role=3 这类
     * 含义不明的值，静默兜底会让脏数据获得实际权限。
     */
    public static UserRole from(String value) {
        if (value == null || value.isBlank()) {
            throw BusinessException.forbidden("账号角色未配置，请联系管理员");
        }
        for (UserRole role : values()) {
            if (role.name().equals(value)) {
                return role;
            }
        }
        throw BusinessException.forbidden("账号角色配置异常，请联系管理员");
    }
}
