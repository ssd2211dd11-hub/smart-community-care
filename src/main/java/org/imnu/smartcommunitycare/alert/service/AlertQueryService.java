package org.imnu.smartcommunitycare.alert.service;

import org.imnu.smartcommunitycare.alert.mapper.AlertEventMapper;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEvent;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEventLog;
import org.imnu.smartcommunitycare.alert.model.enums.AlertStatus;
import org.imnu.smartcommunitycare.alert.model.response.AlertEventResponse;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.elder.service.ElderProfileService;
import org.imnu.smartcommunitycare.user.mapper.SysUserMapper;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 事件查询与响应组装。
 *
 * <p>查询同样受对象级授权约束：老人只看自己的，服务人员只看分派给自己的，
 * 管理员看全部。不存在"换个 ID 就能看别人事项"的路径。
 */
@Service
@RequiredArgsConstructor
public class AlertQueryService {

    private final AlertEventMapper eventMapper;
    private final ElderProfileMapper elderProfileMapper;
    private final SysUserMapper userMapper;
    private final ElderProfileService elderProfileService;
    private final AlertEventService alertEventService;
    private final org.imnu.smartcommunitycare.alert.mapper.AlertEventLogMapper logMapper;
    @Transactional(readOnly=true)
    public org.imnu.smartcommunitycare.common.api.PageQuery.PageResult<org.imnu.smartcommunitycare.alert.mapper.AlertEventLogMapper.Participation> participationPage(
            org.imnu.smartcommunitycare.common.api.PageQuery query) {
        LoginContext.requireAnyRole(UserRole.SERVICE_STAFF);Long staff=LoginContext.requireUserId();
        return org.imnu.smartcommunitycare.common.api.PageQuery.PageResult.of(logMapper.participationPage(staff,query.safePageSize(),query.offset()),
                logMapper.participationCount(staff),query.safePageNum(),query.safePageSize());
    }

    @Transactional(readOnly=true)
    public org.imnu.smartcommunitycare.common.api.PageQuery.PageResult<AlertEventResponse> page(
            org.imnu.smartcommunitycare.alert.model.request.AlertPageQuery query) {
        UserRole role=LoginContext.requireRole();
        var filter=com.baomidou.mybatisplus.core.toolkit.Wrappers.<AlertEvent>lambdaQuery();
        if(role==UserRole.SERVICE_STAFF) {
            if(query.getElderId()!=null) throw BusinessException.forbidden("服务人员无权按老人查询档案事项");
            filter.eq(AlertEvent::getHandlerId,LoginContext.requireUserId());
        } else if(role==UserRole.ELDER) filter.eq(AlertEvent::getElderId,elderProfileService.resolveTargetElderId(query.getElderId()));
        else if(query.getElderId()!=null) filter.eq(AlertEvent::getElderId,elderProfileService.resolveTargetElderId(query.getElderId()));
        String scope=query.getScope();
        if("CURRENT".equals(scope))filter.in(AlertEvent::getStatus,"PENDING","ASSIGNED","PROCESSING");
        else if("HISTORY".equals(scope))filter.in(AlertEvent::getStatus,"CLOSED","CANCELLED");
        else if(!"ALL".equals(scope))throw BusinessException.badRequest("事项范围不合法");
        if(query.getStatus()!=null&&!query.getStatus().isBlank()) {
            AlertStatus.from(query.getStatus());filter.eq(AlertEvent::getStatus,query.getStatus());
        }
        if(query.getSourceType()!=null&&!query.getSourceType().isBlank()) {
            if(!java.util.Set.of("HEALTH_ALERT","HELP_CALL").contains(query.getSourceType()))throw BusinessException.badRequest("事项来源不合法");
            filter.eq(AlertEvent::getSourceType,query.getSourceType());
        }
        if(Boolean.TRUE.equals(query.getUnresponded()))filter.in(AlertEvent::getStatus,"PENDING","ASSIGNED").isNull(AlertEvent::getFirstAcceptedAt);
        if(query.getFromDate()!=null&&query.getToDate()!=null&&query.getFromDate().isAfter(query.getToDate()))throw BusinessException.badRequest("开始日期不能晚于结束日期");
        if(java.time.LocalDate.MAX.equals(query.getToDate()))throw BusinessException.badRequest("结束日期超出范围");
        if(query.getFromDate()!=null)filter.ge(AlertEvent::getCreatedAt,query.getFromDate().atStartOfDay());
        if(query.getToDate()!=null)filter.lt(AlertEvent::getCreatedAt,query.getToDate().plusDays(1).atStartOfDay());
        long total=eventMapper.selectCount(filter);
        var rows=eventMapper.selectList(filter.orderByDesc(AlertEvent::getCreatedAt).orderByDesc(AlertEvent::getId)
                .last("LIMIT "+query.safePageSize()+" OFFSET "+query.offset()));
        return org.imnu.smartcommunitycare.common.api.PageQuery.PageResult.of(rows.stream().map(this::toResponse).toList(),total,query.safePageNum(),query.safePageSize());
    }

    /**
     * 按当前角色返回可见的事件列表。
     */
    public List<AlertEventResponse> listVisible(Long elderId) {
        UserRole role = LoginContext.requireRole();
        List<AlertEvent> events = switch (role) {
            case COMMUNITY_ADMIN -> eventMapper.findAllOpen();
            case ELDER -> eventMapper.findAllByElder(elderProfileService.ownElderId());
            case SERVICE_STAFF -> eventMapper.findOpenByHandler(LoginContext.requireUserId());
        };
        return events.stream().map(this::toResponse).toList();
    }

    /**
     * 查单条事件并校验访问权。
     */
    @Transactional(readOnly = true)
    public AlertEventResponse detail(Long eventId) {
        AlertEvent event = eventMapper.selectById(eventId);
        if (event == null) {
            throw BusinessException.notFound("事项不存在");
        }
        requireVisible(event);
        return toResponseWithTimeline(event);
    }

    /**
     * 校验当前调用者能否看到该事件。
     *
     * <p>越权按 404 处理：不区分"不存在"与"无权"，避免泄露其他老人是否有事项。
     */
    public void requireVisible(AlertEvent event) {
        UserRole role = LoginContext.requireRole();
        switch (role) {
            case COMMUNITY_ADMIN -> {
                return;
            }
            case ELDER -> {
                if (!event.getElderId().equals(elderProfileService.ownElderId())) {
                    throw BusinessException.notFound("事项不存在");
                }
            }
            case SERVICE_STAFF -> {
                // 服务人员只能看到分派给自己的
                Long me = LoginContext.requireUserId();
                if (event.getHandlerId() == null || !event.getHandlerId().equals(me)) {
                    throw BusinessException.notFound("事项不存在");
                }
            }
        }
    }

    public AlertEventResponse toResponse(AlertEvent event) {
        return buildResponse(event, List.of());
    }

    public AlertEventResponse toResponseWithTimeline(AlertEvent event) {
        return buildResponse(event, alertEventService.timeline(event.getId()));
    }

    private AlertEventResponse buildResponse(AlertEvent event, List<AlertEventLog> logs) {
        return new AlertEventResponse(
                event.getId(),
                event.getEventNo(),
                event.getSourceType(),
                "HEALTH_ALERT".equals(event.getSourceType()) ? "健康待核实" : "主动求助",
                event.getRuleCode(),
                event.getElderId(),
                elderName(event.getElderId()),
                event.getStatus(),
                AlertStatus.from(event.getStatus()).label(),
                event.getPriority(),
                event.getHandlerId(),
                userName(event.getHandlerId()),
                event.getOccurrenceCount(),
                event.getResponseDueAt(),
                event.getFirstAcceptedAt(),
                event.getClosedAt(),
                event.getCloseResult(),
                event.getCloseNote(),
                event.getCallType(),
                event.getContactPhone(),
                event.getDescription(),
                event.getVersion(),
                alertEventService.isFirstResponseOverdue(event),
                event.getCreatedAt(),
                logs.stream().map(this::toLogEntry).toList());
    }

    private AlertEventResponse.LogEntry toLogEntry(AlertEventLog log) {
        return new AlertEventResponse.LogEntry(
                log.getAction(),
                log.getFromStatus(),
                log.getToStatus(),
                userName(log.getOperatorId()),
                log.getOperatorSource(),
                log.getReason(),
                log.getCreatedAt());
    }

    private String elderName(Long elderId) {
        if (elderId == null) {
            return null;
        }
        ElderProfile profile = elderProfileMapper.selectById(elderId);
        if (profile == null) {
            return null;
        }
        SysUser user = userMapper.selectById(profile.getUserId());
        return user == null ? null : user.getRealName();
    }

    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = userMapper.selectById(userId);
        return user == null ? null : user.getRealName();
    }
}
