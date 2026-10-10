package org.imnu.smartcommunitycare.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.imnu.smartcommunitycare.auth.model.request.LoginRequest;
import org.imnu.smartcommunitycare.auth.model.request.RegisterRequest;
import org.imnu.smartcommunitycare.auth.model.response.LoginResponse;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.imnu.smartcommunitycare.user.model.response.UserResponse;
import org.imnu.smartcommunitycare.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证与账号服务。
 *
 * <p>事务放在对外调用的公开方法上。同类内部直接调用带 {@code @Transactional}
 * 的方法不会触发代理，这是 Spring 声明式事务的固有行为（V1.0 §10.4）。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final ElderProfileMapper elderProfileMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 登录。
     *
     * <p>每次登录都重新查库确认账号状态，而不是只看会话里的副本——
     * 账号被停用后，已有的会话必须立刻失效。
     */
    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.findByUsername(request.username());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // 用户不存在与密码错误返回同一提示，避免暴露"该用户名是否存在"
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw BusinessException.forbidden("账号已停用，请联系社区管理员");
        }

        // 角色从库中读出；UserRole.from 遇未知角色会抛 403，
        // 不需要也不应该在这里再包一层try-catch —— 那会把明确的权限错误
        // 变成"角色配置异常"的二次包装，掩盖真实原因。
        UserRole role = UserRole.from(user.getRole());

        StpUtil.login(user.getId());
        StpUtil.getSession().set("role", role.name());

        return new LoginResponse(
                StpUtil.getTokenValue(),
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                role.name());
    }

    /**
     * 退出登录。令牌立即失效。
     */
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 查询当前登录账号。
     */
    public UserResponse currentUser() {
        Long userId = Long.valueOf(StpUtil.getLoginIdAsString());
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw BusinessException.unauthorized("账号不存在");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            // 会话还在但账号已停用，主动踢下线
            StpUtil.logout();
            throw BusinessException.forbidden("账号已停用");
        }
        return toResponse(user);
    }

    /**
     * 公开注册。<b>固定创建老人账号，不接受前端传入角色。</b>
     */
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        SysUser existing = userMapper.findByUsername(request.username());
        if (existing != null) {
            throw BusinessException.conflict("用户名已被占用");
        }

        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRealName(request.realName());
        user.setPhone(blankToNull(request.phone()));
        user.setEmail(blankToNull(request.email()));
        // 公开注册只能是老人，前端无从干预
        user.setRole(UserRole.ELDER.name());
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        // 同一事务建立老人档案：只有账号没有档案的老人，
        // 登录后进不去任何页面（健康记录、事项都按 elderId 归属）。
        // 档案的出生日期等资料由老人登录后在个人资料页补全。
        ElderProfile profile = new ElderProfile();
        profile.setUserId(user.getId());
        profile.setCreatedAt(user.getCreatedAt());
        profile.setUpdatedAt(user.getUpdatedAt());
        elderProfileMapper.insert(profile);
    }

    /**
     * 实体转响应 DTO。
     *
     * <p>显式逐字段赋值，不整包拷贝。这样 passwordHash 天然不会出现在响应里——
     * 少写一个字段就是少漏一个字段，靠 {@code BeanUtils.copyProperties} 做不到这点。
     */
    private UserResponse toResponse(SysUser user) {
        // 仅老人角色读取本人的老人档案字段。
        String birthDate = null;
        String gender = null;
        String address = null;
        String emergencyContact = null;
        String emergencyPhone = null;
        if (user.getRole() != null && UserRole.ELDER.name().equals(user.getRole())) {
            ElderProfile profile = elderProfileMapper.findByUserId(user.getId());
            if (profile != null) {
                birthDate = profile.getBirthDate() == null ? null : profile.getBirthDate().toString();
                gender = profile.getGender();
                address = profile.getAddress();
                emergencyContact = profile.getEmergencyContact();
                emergencyPhone = profile.getEmergencyPhone();
            }
        }

        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getPhone(),
                user.getEmail(),
                UserRole.from(user.getRole()),
                user.getEnabled(),
                user.getRemark(),
                birthDate,
                gender,
                address,
                emergencyContact,
                emergencyPhone);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
