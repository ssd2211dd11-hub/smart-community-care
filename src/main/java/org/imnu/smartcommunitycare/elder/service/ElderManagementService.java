package org.imnu.smartcommunitycare.elder.service;

import lombok.RequiredArgsConstructor;
import org.imnu.smartcommunitycare.common.api.PageQuery.PageResult;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.elder.model.request.UpdateElderRequest;
import org.imnu.smartcommunitycare.elder.model.response.ElderSummary;
import org.imnu.smartcommunitycare.user.mapper.SysUserMapper;
import org.imnu.smartcommunitycare.user.model.request.AdminAccountQuery;
import org.imnu.smartcommunitycare.user.model.response.AdminAccountResponse;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

@Service @RequiredArgsConstructor
public class ElderManagementService {
    private final ElderProfileMapper profileMapper;
    private final SysUserMapper userMapper;
    public record Detail(AdminAccountResponse account,ElderProfile profile) {}

    @Transactional(readOnly=true)
    public PageResult<ElderSummary> list(AdminAccountQuery query) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        String key=query.getKeyword()==null?null:query.getKeyword().trim();
        long total=profileMapper.countFiltered(key,query.getEnabled());
        return PageResult.of(profileMapper.page(key,query.getEnabled(),query.safePageSize(),query.offset()),
                total,query.safePageNum(),query.safePageSize());
    }
    public Detail detail(Long id) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        ElderProfile profile=profileMapper.selectById(id);
        if(profile==null) throw BusinessException.notFound("老人档案不存在");
        var user=userMapper.selectById(profile.getUserId());
        if(user==null) throw BusinessException.notFound("关联账号不存在");
        return new Detail(AdminAccountResponse.from(user),profile);
    }
    @Transactional(rollbackFor=Exception.class)
    public void update(Long id,UpdateElderRequest request) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        ElderProfile profile=profileMapper.findByIdForUpdate(id);
        if(profile==null) throw BusinessException.notFound("老人档案不存在");
        if(request.birthDate()!=null&&!request.birthDate().isBlank()) {
            LocalDate birth;
            try {birth=LocalDate.parse(request.birthDate());}
            catch(java.time.format.DateTimeParseException e){throw BusinessException.badRequest("出生日期格式应为yyyy-MM-dd");}
            if(birth.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))) throw BusinessException.badRequest("出生日期不能晚于今天");
            profile.setBirthDate(birth);
        }
        if(request.gender()!=null) {
            if(!request.gender().isBlank()&&!Set.of("male","female","other").contains(request.gender()))
                throw BusinessException.badRequest("性别取值不合法");
            profile.setGender(request.gender());
        }
        if(request.address()!=null) profile.setAddress(request.address().trim());
        if(request.emergencyContact()!=null) profile.setEmergencyContact(request.emergencyContact().trim());
        if(request.emergencyPhone()!=null) profile.setEmergencyPhone(request.emergencyPhone().trim());
        if(request.careNote()!=null) profile.setCareNote(request.careNote().trim());
        profile.setUpdatedAt(LocalDateTime.now());profileMapper.updateById(profile);
    }
}
