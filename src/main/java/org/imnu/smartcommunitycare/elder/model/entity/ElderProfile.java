package org.imnu.smartcommunitycare.elder.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 老人档案实体。
 *
 * <p>一个老人账号对应一份档案（user_id 唯一，见 uk_elder_profile_user）。
 * {@code userId} 是账号 ID，档案自身的 {@code id} 才是 {@code elderId}，
 * 两者不能混用——业务表里一律存 elderId。
 */
@Getter
@Setter
@TableName("elder_profile")
public class ElderProfile {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using=ToStringSerializer.class)
    private Long id;

    /** 对应 sys_user.id，该账号角色为 ELDER */
    @JsonSerialize(using=ToStringSerializer.class)
    private Long userId;

    /** 出生日期。用日期而非年龄，年龄会随时间失真 */
    private LocalDate birthDate;

    /** male / female / other */
    private String gender;

    private String address;

    /** 紧急联系人姓名。只是联系信息，不会自动成为系统用户 */
    private String emergencyContact;

    private String emergencyPhone;

    /** 照护备注：人工记录，不作为任何模型输入，也不转化为诊断 */
    private String careNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
