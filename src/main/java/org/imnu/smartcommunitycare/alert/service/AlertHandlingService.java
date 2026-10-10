package org.imnu.smartcommunitycare.alert.service;

import org.imnu.smartcommunitycare.alert.mapper.AlertEventLogMapper;
import org.imnu.smartcommunitycare.alert.mapper.AlertEventMapper;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEvent;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEventLog;
import org.imnu.smartcommunitycare.alert.model.enums.AlertStatus;
import org.imnu.smartcommunitycare.alert.model.enums.CloseResult;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.user.mapper.SysUserMapper;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件处置：认领、分派、接收、转派、关闭、撤销。
 *
 * <p>每一步都遵循同一条顺序：
 * <b>锁行 → 校验状态前置 → 校验权限 → 带版本更新 → 校验影响行数 → 写时间线</b>。
 *
 * <p>顺序不能换。先更新再校验的话，并发的两个管理员会都"成功"；
 * 先写日志再改状态的话，失败后会留下没有状态变更的假日志。
 */
@Service
@RequiredArgsConstructor
public class AlertHandlingService {

    private final AlertEventMapper eventMapper;
    private final AlertEventLogMapper logMapper;
    private final SysUserMapper userMapper;
    private final ElderProfileMapper elderMapper;

    private final Clock clock;

    /**
     * 管理员认领：待处理 → 处理中。
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertEvent claim(Long eventId, Integer clientVersion) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        Long operatorId = LoginContext.requireUserId();

        AlertEvent event = lockOrFail(eventId);
        AlertStatus.from(event.getStatus()).requireCanMoveTo(AlertStatus.PROCESSING, "认领");

        int affected = eventMapper.updateStatusWithVersion(
                eventId, AlertStatus.PROCESSING.name(), operatorId, requireVersion(event, clientVersion));
        requireUpdated(affected);

        // 认领即首次接收：写一次，之后转派不再改写
        markFirstAccepted(eventId);
        writeLog(eventId, "CLAIM", event.getStatus(), AlertStatus.PROCESSING.name(),
                operatorId, "USER", "管理员认领");
        return lockOrFail(eventId);
    }

    /**
     * 管理员分派给指定处理人：待处理 → 待接收。
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertEvent assign(Long eventId, Long assigneeId, String reason, Integer clientVersion) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        Long operatorId = LoginContext.requireUserId();

        AlertEvent event = lockOrFail(eventId);
        AlertStatus.from(event.getStatus()).requireCanMoveTo(AlertStatus.ASSIGNED, "分派");

        SysUser assignee = userMapper.selectById(assigneeId);
        if (assignee == null) {
            throw BusinessException.notFound("指定的处理人不存在");
        }
        if (!Boolean.TRUE.equals(assignee.getEnabled())) {
            throw BusinessException.badRequest("指定的处理人账号已停用");
        }
        if (assignee.getRole() == null
                || (!UserRole.COMMUNITY_ADMIN.name().equals(assignee.getRole())
                && !UserRole.SERVICE_STAFF.name().equals(assignee.getRole()))) {
            throw BusinessException.badRequest("只能分派给管理员或服务人员");
        }

        int affected = eventMapper.updateStatusWithVersion(
                eventId, AlertStatus.ASSIGNED.name(), assigneeId, requireVersion(event, clientVersion));
        requireUpdated(affected);

        writeLog(eventId, "ASSIGN", event.getStatus(), AlertStatus.ASSIGNED.name(),
                operatorId, "USER", reason);
        return lockOrFail(eventId);
    }

    /**
     * 受派人接收：待接收 → 处理中。
     *
     * <p>只有当前受派人本人能接收，其他角色一律拒绝——
     * 这条不能靠"前端不显示按钮"保证。
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertEvent accept(Long eventId, Integer clientVersion) {
        Long operatorId = LoginContext.requireUserId();
        AlertEvent event = lockOrFail(eventId);

        if (event.getHandlerId() == null || !event.getHandlerId().equals(operatorId)) {
            throw BusinessException.forbidden("只有当前受派人可以接收该事项");
        }
        AlertStatus.from(event.getStatus()).requireCanMoveTo(AlertStatus.PROCESSING, "接收");

        int affected = eventMapper.updateStatusWithVersion(
                eventId, AlertStatus.PROCESSING.name(), operatorId, requireVersion(event, clientVersion));
        requireUpdated(affected);

        markFirstAccepted(eventId);
        writeLog(eventId, "ACCEPT", event.getStatus(), AlertStatus.PROCESSING.name(),
                operatorId, "USER", "受派人接收");
        return lockOrFail(eventId);
    }

    /**
     * 管理员转派：交给别人。
     *
     * <p>转派<b>不清空首次接收时间</b>——首次响应指标反映的是
     * "这件事多久有人开始处理"，不是"现在归谁"。
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertEvent transfer(Long eventId, Long newHandlerId, String reason, Integer clientVersion) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        Long operatorId = LoginContext.requireUserId();

        if (reason == null || reason.isBlank()) {
            throw BusinessException.badRequest("转派必须填写原因");
        }
        AlertEvent event = lockOrFail(eventId);
        AlertStatus.from(event.getStatus()).requireCanMoveTo(AlertStatus.ASSIGNED, "转派");

        SysUser newHandler = userMapper.selectById(newHandlerId);
        if (newHandler == null) {
            throw BusinessException.notFound("指定的处理人不存在");
        }
        if (!Boolean.TRUE.equals(newHandler.getEnabled())) {
            throw BusinessException.badRequest("指定的处理人账号已停用");
        }

        if (!UserRole.COMMUNITY_ADMIN.name().equals(newHandler.getRole())
                && !UserRole.SERVICE_STAFF.name().equals(newHandler.getRole())) {
            throw BusinessException.badRequest("只能转派给管理员或服务人员");
        }

        int affected = eventMapper.updateStatusWithVersion(
                eventId, AlertStatus.ASSIGNED.name(), newHandlerId, requireVersion(event, clientVersion));
        requireUpdated(affected);

        writeLog(eventId, "TRANSFER", event.getStatus(), AlertStatus.ASSIGNED.name(),
                operatorId, "USER", "转派：" + reason);
        return lockOrFail(eventId);
    }

    /**
     * 关闭：处理中 → 已关闭。
     *
     * <p>只有当前处理人或管理员能关。关闭必须写结果与说明——
     * 不允许"处理完了"之外的其他含糊结论。
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertEvent close(Long eventId, CloseResult result, String note, Integer clientVersion) {
        Long operatorId = LoginContext.requireUserId();
        AlertEvent event = lockOrFail(eventId);

        if (!isAdmin() && (event.getHandlerId() == null || !event.getHandlerId().equals(operatorId))) {
            throw BusinessException.forbidden("只有当前处理人或管理员可以关闭该事项");
        }
        AlertStatus.from(event.getStatus()).requireCanMoveTo(AlertStatus.CLOSED, "关闭");

        if (note == null || note.isBlank()) {
            throw BusinessException.badRequest("关闭必须填写处理说明");
        }

        int affected = eventMapper.closeEvent(
                eventId, result.name(), note, LocalDateTime.now(clock), requireVersion(event, clientVersion));
        requireUpdated(affected);

        writeLog(eventId, "CLOSE", event.getStatus(), AlertStatus.CLOSED.name(),
                operatorId, "USER", result.name() + "：" + note);
        return lockOrFail(eventId);
    }

    /**
     * 撤销：仅主动求助，待处理 → 已撤销。
     *
     * <p>健康告警不能撤销——它由规则命中产生，不是某个人提交的请求。
     * 已分派的求助也不由发起人撤回，需要处理人核实后关闭。
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertEvent cancel(Long eventId, String reason, Integer clientVersion) {
        Long operatorId = LoginContext.requireUserId();
        AlertEvent event = lockOrFail(eventId);
        LoginContext.requireAnyRole(UserRole.ELDER);
        var elder = elderMapper.findByUserId(operatorId);
        if (elder == null || !elder.getId().equals(event.getElderId())) {
            throw BusinessException.notFound("事项不存在");
        }


        if (!"HELP_CALL".equals(event.getSourceType())) {
            throw BusinessException.badRequest("健康告警不能撤销，请由处理人核实后关闭");
        }
        if (event.getHandlerId() != null) {
            throw BusinessException.conflict("该求助已分派给处理人，需由其核实后关闭");
        }
        AlertStatus.from(event.getStatus()).requireCanMoveTo(AlertStatus.CANCELLED, "撤销");

        if (reason == null || reason.isBlank()) {
            throw BusinessException.badRequest("撤销必须填写原因");
        }

        int affected = eventMapper.cancelEvent(eventId, reason, requireVersion(event, clientVersion));
        requireUpdated(affected);

        writeLog(eventId, "CANCEL", event.getStatus(), AlertStatus.CANCELLED.name(),
                operatorId, "USER", reason);
        return lockOrFail(eventId);
    }

    /** 页面读取的版本参与行锁后的比较，不能用刚读到的版本替代客户端版本。 */
    private int requireVersion(AlertEvent event, Integer version) {
        if (version == null || version < 0) {
            throw BusinessException.badRequest("请携带有效的事项版本");
        }
        if (!version.equals(event.getVersion())) {
            throw BusinessException.conflict("事项状态已被他人变更，请刷新后重试");
        }
        return version;
    }

    /** 锁定事件；不存在时按 404 处理 */
    private AlertEvent lockOrFail(Long eventId) {
        AlertEvent event = eventMapper.findByIdForUpdate(eventId);
        if (event == null) {
            throw BusinessException.notFound("事项不存在");
        }
        return event;
    }

    /**
     * 版本不符时明确提示刷新。
     *
     * <p>影响行数为 0 说明别人先改过了。让调用方刷新重试，
     * 而不是静默"再试一次覆盖"——那会丢掉对方的修改。
     */
    private void requireUpdated(int affected) {
        if (affected != 1) {
            throw BusinessException.conflict("事项状态已被他人变更，请刷新后重试");
        }
    }

    /** 首次接收时间：只在为空时写，转派不清空 */
    private void markFirstAccepted(Long eventId) {
        eventMapper.fillFirstAcceptedAt(eventId, LocalDateTime.now(clock));
    }

    private void writeLog(Long eventId, String action, String fromStatus, String toStatus,
                          Long operatorId, String operatorSource, String reason) {
        AlertEventLog log = new AlertEventLog();
        log.setEventId(eventId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperatorId(operatorId);
        log.setOperatorSource(operatorSource);
        log.setReason(reason);
        log.setCreatedAt(LocalDateTime.now(clock));
        logMapper.insert(log);
    }

    private boolean isAdmin() {
        return LoginContext.requireRole() == UserRole.COMMUNITY_ADMIN;
    }

    /** 事件时间线 */
    public List<AlertEventLog> timeline(Long eventId) {
        return logMapper.findByEventId(eventId);
    }
}
