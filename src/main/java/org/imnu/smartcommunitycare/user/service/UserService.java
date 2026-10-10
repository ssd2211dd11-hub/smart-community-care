package org.imnu.smartcommunitycare.user.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.user.mapper.SysUserMapper;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.imnu.smartcommunitycare.user.model.request.ChangePasswordRequest;
import org.imnu.smartcommunitycare.user.model.request.UpdateMyProfileRequest;
import org.imnu.smartcommunitycare.user.model.response.AssignableStaffResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 账号服务：修改本人密码、账号启停。
 *
 * <p>与 {@code AuthService} 的分工：登录注册属于 auth 模块，
 * 账号本身的维护放在这里。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper elderProfileMapper;

    /**
     * 更新当前会话用户的联系方式及本人老人档案。
     * 角色、启用状态、账号名和资源归属保持不变，已下单的联系人与地址快照不追溯修改。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateMyProfile(UpdateMyProfileRequest request) {
        Long userId = LoginContext.requireUserId();
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.notFound("账号不存在");
        }

        // null 表示「不改」，未传的字段保持原值
        String realName = request.realName() != null ? request.realName().trim() : user.getRealName();
        String phone = request.phone() != null ? request.phone().trim() : user.getPhone();
        String email = request.email() != null ? request.email().trim() : user.getEmail();

        if (realName != null && realName.isBlank()) {
            throw BusinessException.badRequest("姓名不能为空");
        }

        SysUser target = new SysUser();
        target.setId(userId);
        target.setRealName(realName);
        target.setPhone(phone);
        target.setEmail(email);
        target.setUpdatedAt(LocalDateTime.now());
        // role / enabled / username 一个都不 set：
        // MyBatis-Plus 的 updateById 只更新非 null 字段，
        // 那些列在 SQL 里根本不出现，数据库原值保持不变。
        userMapper.updateById(target);

        if (UserRole.ELDER.name().equals(user.getRole())) {
            updateElderProfileFields(userId, request);
        }
    }

    /**
     * 更新老人档案中由本人维护的字段。
     *
     * <p>{@code careNote} 是工作人员填写的照护备注，<b>本人不可改</b>，
     * 这里刻意不碰。
     */
    private void updateElderProfileFields(Long userId, UpdateMyProfileRequest request) {
        org.imnu.smartcommunitycare.elder.model.entity.ElderProfile profile =
                elderProfileMapper.findByUserId(userId);
        if (profile == null) {
            // 没有档案就只更新账号，不凭空建档——建档是注册流程的职责
            return;
        }

        if (request.birthDate() != null && !request.birthDate().isBlank()) {
            try {
                profile.setBirthDate(java.time.LocalDate.parse(request.birthDate().trim()));
            } catch (java.time.format.DateTimeParseException e) {
                throw BusinessException.badRequest("出生日期格式应为 yyyy-MM-dd");
            }
        }
        if (request.gender() != null) {
            profile.setGender(request.gender().trim());
        }
        if (request.address() != null) {
            profile.setAddress(request.address().trim());
        }
        if (request.emergencyContact() != null) {
            profile.setEmergencyContact(request.emergencyContact().trim());
        }
        if (request.emergencyPhone() != null) {
            profile.setEmergencyPhone(request.emergencyPhone().trim());
        }
        profile.setUpdatedAt(LocalDateTime.now());
        elderProfileMapper.updateById(profile);
    }

    /**
     * 修改本人密码。
     *
     * <p>改密成功后<b>立即踢下线</b>，要求用新密码重新登录。
     * 原因：密码变更是账号被盗后的标准止损动作，如果改完旧会话还能用，
     * 攻击者改完密码照样能继续操作，失去意义。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeOwnPassword(ChangePasswordRequest request) {
        Long userId = LoginContext.requireUserId();
        SysUser user = userMapper.findByIdForUpdate(userId);
        if (user == null) {
            throw BusinessException.notFound("账号不存在");
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw BusinessException.badRequest("原密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw BusinessException.badRequest("新密码不能与原密码相同");
        }

        SysUser update = new SysUser();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);

        revokeAfterCommit(userId);
    }

    /**
     * 停用账号。
     *
     * <p>停用后不能登录，也不能继续用旧会话写入；<b>不物理删除账号与其历史记录</b>
     * （V1.2 §3.3）。历史业务数据必须保留。
     */
    @Transactional(rollbackFor = Exception.class)
    public void disableAccount(Long userId) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);

        List<SysUser> administrators = userMapper.lockAdministrators();
        SysUser user = userMapper.findByIdForUpdate(userId);
        if (user == null) {
            throw BusinessException.notFound("账号不存在");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw BusinessException.badRequest("账号已是停用状态");
        }

        // 先做不可逆动作之前的校验：若会停用最后一个管理员，立即拒绝，
        // 不写库也不踢会话。顺序反过来会出现"事务回滚了但会话已被踢"的副作用残留。
        if (UserRole.COMMUNITY_ADMIN.name().equals(user.getRole()) && administrators.stream()
                .filter(admin -> Boolean.TRUE.equals(admin.getEnabled())).count() <= 1) {
            throw BusinessException.badRequest("不能停用最后一个启用的社区管理员");
        }

        SysUser update = new SysUser();
        update.setId(userId);
        update.setEnabled(false);
        update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);

        // 停用成功后立刻撤销该账号可能存在的会话
        revokeAfterCommit(userId);
    }

    /**
     * 列出可作为分派/转派目标的启用服务人员。
     *
     * <p><b>只返回 id、姓名、角色</b>：这是分派动作真正需要的字段。
     * 不返回密码哈希、联系方式或任何健康资料——页面用不到就不该传出去。
     *
     * <p>只列启用的：停用人员不能被新分派（V1.2 §3.3）。
     */
    public List<AssignableStaffResponse> listAssignableStaff() {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);

        return userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getRole, UserRole.SERVICE_STAFF.name())
                        .eq(SysUser::getEnabled, true)
                        .orderByAsc(SysUser::getId))
                .stream()
                .map(u -> new AssignableStaffResponse(u.getId(), u.getRealName(), u.getRole()))
                .toList();
    }

    private void revokeAfterCommit(Long userId) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { StpUtil.logout(userId); }
                });
    }
}
