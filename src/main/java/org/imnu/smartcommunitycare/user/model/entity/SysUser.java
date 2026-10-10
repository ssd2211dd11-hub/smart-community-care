package org.imnu.smartcommunitycare.user.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 账号实体。
 *
 * <p>注意：此实体仅用于数据访问，**不作为接口响应返回**。
 * 业务响应使用 {@code UserResponse}，避免把 password_hash 带出。
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录名，数据库唯一 */
    private String username;

    /** BCrypt 哈希，不回传业务页面 */
    private String passwordHash;

    private String realName;

    private String phone;

    private String email;

    /** COMMUNITY_ADMIN / ELDER / SERVICE_STAFF */
    private String role;

    /** 停用后不能登录与写入，历史记录保留 */
    private Boolean enabled;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
