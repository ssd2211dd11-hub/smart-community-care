package org.imnu.smartcommunitycare.user.model.request;

import jakarta.validation.constraints.Size;

/**
 * 本人资料更新请求；归属取自会话，角色与启用状态由管理员维护。
 * null表示保留原值；老人档案字段仅对老人角色生效。
 * @param realName          姓名
 * @param phone             联系电话
 * @param email             邮箱
 * @param birthDate         老人出生日期（仅老人档案，其余角色忽略）
 * @param gender            老人性别（仅老人档案）
 * @param address           老人住址（仅老人档案）
 * @param emergencyContact  紧急联系人姓名（仅老人档案；<b>只是档案字段，不建账号</b>）
 * @param emergencyPhone    紧急联系人电话（仅老人档案）
 */
public record UpdateMyProfileRequest(
        @Size(max = 50, message = "姓名不能超过 50 字")
        String realName,

        @Size(max = 20, message = "联系电话不能超过 20 位")
        String phone,

        @Size(max = 100, message = "邮箱不能超过 100 字")
        String email,

        String birthDate,

        @Size(max = 10, message = "性别取值过长")
        String gender,

        @Size(max = 200, message = "住址不能超过 200 字")
        String address,

        @Size(max = 50, message = "紧急联系人姓名不能超过 50 字")
        String emergencyContact,

        @Size(max = 20, message = "紧急联系人电话不能超过 20 位")
        String emergencyPhone
) {
}
