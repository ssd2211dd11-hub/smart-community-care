package org.imnu.smartcommunitycare.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.imnu.smartcommunitycare.common.api.PageQuery.PageResult;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.user.mapper.SysUserMapper;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.imnu.smartcommunitycare.user.model.request.*;
import org.imnu.smartcommunitycare.user.model.response.AdminAccountResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Arrays;

@Service @RequiredArgsConstructor
public class UserManagementService {
    private final SysUserMapper userMapper;
    private final ElderProfileMapper elderProfileMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    public AdminAccountResponse detail(Long userId) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        SysUser user=userMapper.selectById(userId);
        if(user==null) throw BusinessException.notFound("账号不存在");
        return AdminAccountResponse.from(user);
    }

    @Transactional(readOnly=true)
    public PageResult<AdminAccountResponse> listAccounts(AdminAccountQuery query) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        var filter=Wrappers.<SysUser>lambdaQuery();
        if(query.getKeyword()!=null&&!query.getKeyword().isBlank()) {
            String key=query.getKeyword().trim();
            filter.and(w->w.like(SysUser::getUsername,key).or().like(SysUser::getRealName,key));
        }
        if(query.getRole()!=null&&!query.getRole().isBlank()) {
            if(Arrays.stream(UserRole.values()).noneMatch(r->r.name().equals(query.getRole())))
                throw BusinessException.badRequest("账号角色不合法");
            filter.eq(SysUser::getRole,query.getRole());
        }
        if(query.getEnabled()!=null) filter.eq(SysUser::getEnabled,query.getEnabled());
        long total=userMapper.selectCount(filter);
        var rows=userMapper.selectList(filter.orderByDesc(SysUser::getCreatedAt).orderByDesc(SysUser::getId)
                .last("LIMIT "+query.safePageSize()+" OFFSET "+query.offset()));
        return PageResult.of(rows.stream().map(AdminAccountResponse::from).toList(),total,query.safePageNum(),query.safePageSize());
    }

    @Transactional(rollbackFor=Exception.class)
    public void createAccount(CreateAccountRequest request) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        if(!UserRole.ELDER.name().equals(request.role())&&!UserRole.SERVICE_STAFF.name().equals(request.role()))
            throw BusinessException.badRequest("仅可创建老人或服务人员账号");
        if(request.realName().isBlank()) throw BusinessException.badRequest("姓名不能为空");
        if(userMapper.findByUsername(request.username())!=null) throw BusinessException.conflict("用户名已被占用");
        SysUser user=new SysUser(); user.setUsername(request.username());
        user.setPasswordHash(passwordEncoder.encode(request.password())); user.setRealName(request.realName().trim());
        user.setPhone(request.phone()); user.setEmail(request.email()); user.setRole(request.role()); user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now()); user.setUpdatedAt(user.getCreatedAt());
        try {
            userMapper.insert(user);
            if(UserRole.ELDER.name().equals(request.role())) {
                ElderProfile profile=new ElderProfile();profile.setUserId(user.getId());
                profile.setCreatedAt(user.getCreatedAt());profile.setUpdatedAt(user.getUpdatedAt());
                elderProfileMapper.insert(profile);
            }
        } catch(DuplicateKeyException e) { throw BusinessException.conflict("用户名或档案已存在"); }
    }

    @Transactional(rollbackFor=Exception.class)
    public void updateAccount(Long userId,UpdateMyProfileRequest request) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        SysUser user=userMapper.findByIdForUpdate(userId);
        if(user==null) throw BusinessException.notFound("账号不存在");
        String name=request.realName()==null?user.getRealName():request.realName().trim();
        if(name==null||name.isBlank()) throw BusinessException.badRequest("姓名不能为空");
        SysUser update=new SysUser();update.setId(userId);update.setRealName(name);
        update.setPhone(request.phone()==null?user.getPhone():request.phone().trim());
        update.setEmail(request.email()==null?user.getEmail():request.email().trim());
        update.setUpdatedAt(LocalDateTime.now());userMapper.updateById(update);
    }

    @Transactional(rollbackFor=Exception.class)
    public void enableAccount(Long userId) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        userMapper.lockAdministrators();
        SysUser user=userMapper.findByIdForUpdate(userId);
        if(user==null) throw BusinessException.notFound("账号不存在");
        if(Boolean.TRUE.equals(user.getEnabled())) throw BusinessException.badRequest("账号已是启用状态");
        SysUser update=new SysUser();update.setId(userId);update.setEnabled(true);update.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(update);
    }
}
