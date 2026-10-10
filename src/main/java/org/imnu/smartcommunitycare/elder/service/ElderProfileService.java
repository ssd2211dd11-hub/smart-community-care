package org.imnu.smartcommunitycare.elder.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 老人档案服务。
 *
 * <p><b>本类是对象级授权的落点</b>：所有"这个老人是谁"的判断都收口在这里，
 * 不让控制器和各个Service 各写一份，越权判断分散就等于没有。
 */
@Service
@RequiredArgsConstructor
public class ElderProfileService {

    private final ElderProfileMapper elderProfileMapper;

    /**
     * 按档案 ID 取档案，并校验当前调用者对该档案的访问权。
     *
     * <p>越权时按 404 处理而不是 403：返回 403 等于告诉攻击者"这个老人存在"，
     * 泄露了对象是否存在。统一 404 让探测者无法区分"不存在"与"无权访问"。
     *
     * @param elderId 目标档案主键
     * @return 通过校验的档案
     */
    public ElderProfile requireAccessible(Long elderId) {
        ElderProfile profile = elderId == null ? null : elderProfileMapper.selectById(elderId);
        if (profile == null) {
            throw BusinessException.notFound("老人档案不存在");
        }
        UserRole role = LoginContext.requireRole();
        if (role == UserRole.COMMUNITY_ADMIN) {
            return profile;
        }
        if (role == UserRole.ELDER) {
            // 老人只能看本人：比对档案关联的账号与当前会话账号
            if (!profile.getUserId().equals(LoginContext.requireUserId())) {
                throw BusinessException.notFound("老人档案不存在");
            }
            return profile;
        }
        // 服务人员没有通用档案查询权限（V1.2 §3.2）
        throw BusinessException.forbidden("当前角色无权查看老人档案");
    }

    /**
     * 取当前登录老人自己的档案。
     *
     * <p>老人端页面用这个，不需要在 URL 里传 elderId——
     * 传了就等于允许改 URL 去查别人。
     */
    public ElderProfile requireOwnProfile() {
        if (LoginContext.requireRole() != UserRole.ELDER) {
            throw BusinessException.forbidden("仅老人用户可访问本人档案");
        }
        Long userId = LoginContext.requireUserId();
        ElderProfile profile = elderProfileMapper.findByUserId(userId);
        if (profile == null) {
            throw BusinessException.notFound("尚未建立老人档案");
        }
        return profile;
    }

    /** 当前登录老人自己的档案 ID */
    public Long ownElderId() {
        return requireOwnProfile().getId();
    }

    /**
     * 解析"要操作哪份档案"。
     *
     * <p>管理员可显式指定 elderId 代录；老人只能操作本人档案。
     *
     * <p><b>老人传入他人 elderId 时明确报 404，而不是静默改成查自己</b>：
     * 静默改写会让调用方拿到一份"看起来为空"的结果，误以为那位老人确实没有记录。
     * 明确拒绝才符合"越权不可见"的语义，也不会误导使用者。
     */
    public Long resolveTargetElderId(Long requestedElderId) {
        if (LoginContext.requireRole() == UserRole.COMMUNITY_ADMIN) {
            if (requestedElderId == null) {
                throw BusinessException.badRequest("代录健康数据时必须指定老人");
            }
            requireAccessible(requestedElderId);
            return requestedElderId;
        }
        if (LoginContext.requireRole() == UserRole.SERVICE_STAFF) {
            throw BusinessException.forbidden("服务人员无权访问健康记录");
        }
        Long ownId = ownElderId();
        if (requestedElderId != null && !requestedElderId.equals(ownId)) {
            // 不区分"不存在"与"无权访问"，避免泄露其他老人档案是否存在
            throw BusinessException.notFound("老人档案不存在");
        }
        return ownId;
    }

    /** 按账号 ID 查档案 */
    public ElderProfile findByUserId(Long userId) {
        return elderProfileMapper.findByUserId(userId);
    }

    /**
     * 管理员创建老人档案。
     *
     * <p>与账号创建同一事务：管理员代建老人账号时，账号与档案必须同时成功，
     * 不能出现"有账号没档案"的半成品。
     */
    public ElderProfile createProfile(ElderProfile profile) {
        if (LoginContext.requireRole() != UserRole.COMMUNITY_ADMIN) {
            throw BusinessException.forbidden("仅管理员可创建老人档案");
        }
        if (elderProfileMapper.findByUserId(profile.getUserId()) != null) {
            throw BusinessException.conflict("该账号已存在老人档案");
        }
        profile.setId(null);
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        elderProfileMapper.insert(profile);
        return profile;
    }

    /** 更新老人自己的资料。角色、启用状态、归属不允许通过这个接口修改 */
    public ElderProfile updateOwnProfile(ElderProfile update) {
        ElderProfile existing = requireOwnProfile();

        // 只允许改这几个字段；其余（id / userId）由后端固定，不接受客户端传入
        ElderProfile target = new ElderProfile();
        target.setId(existing.getId());
        target.setBirthDate(update.getBirthDate());
        target.setGender(update.getGender());
        target.setAddress(update.getAddress());
        target.setEmergencyContact(update.getEmergencyContact());
        target.setEmergencyPhone(update.getEmergencyPhone());
        target.setCareNote(update.getCareNote());
        target.setUpdatedAt(LocalDateTime.now());
        elderProfileMapper.updateById(target);
        return target;
    }

    /** 管理员分页查询全部档案 */
    public List<ElderProfile> listAll() {
        if (LoginContext.requireRole() != UserRole.COMMUNITY_ADMIN) {
            throw BusinessException.forbidden("仅管理员可查询老人档案列表");
        }
        return elderProfileMapper.selectList(Wrappers.<ElderProfile>lambdaQuery());
    }
}
