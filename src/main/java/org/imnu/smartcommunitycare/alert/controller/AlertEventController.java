package org.imnu.smartcommunitycare.alert.controller;

import org.imnu.smartcommunitycare.alert.model.entity.AlertEvent;
import org.imnu.smartcommunitycare.alert.model.enums.CloseResult;
import org.imnu.smartcommunitycare.alert.model.response.AlertEventResponse;
import org.imnu.smartcommunitycare.alert.service.AlertEventService;
import org.imnu.smartcommunitycare.alert.service.AlertHandlingService;
import org.imnu.smartcommunitycare.alert.service.AlertQueryService;
import org.imnu.smartcommunitycare.alert.model.request.CreateHelpCallRequest;
import org.imnu.smartcommunitycare.common.api.ApiResponse;
import org.imnu.smartcommunitycare.elder.service.ElderProfileService;
import jakarta.validation.Valid;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 告警事项接口。
 *
 * <p>动作路径用明确业务动词（claim/assign/accept/transfer/close/cancel），
 * <b>不提供 {@code PUT /{id}/status} 这种接收任意状态的入口</b>——
 * 那会让前端绕过状态机约束。
 */
@RestController
@RequestMapping("/api/alert-events")
@RequiredArgsConstructor
public class AlertEventController {
    @GetMapping("/my-participations/paged")
    public ApiResponse<?> participationPage(org.imnu.smartcommunitycare.common.api.PageQuery query) {
        return ApiResponse.success("查询成功",queryService.participationPage(query));
    }

    @GetMapping("/paged")
    public ApiResponse<?> page(org.imnu.smartcommunitycare.alert.model.request.AlertPageQuery query) {
        return ApiResponse.success("查询成功",queryService.page(query));
    }

    private final AlertQueryService queryService;
    private final AlertHandlingService handlingService;
    private final AlertEventService eventService;
    private final ElderProfileService elderProfileService;

    /** 事项列表：按角色返回各自可见范围 */
    @GetMapping
    public ApiResponse<List<AlertEventResponse>> list() {
        return ApiResponse.success("查询成功", queryService.listVisible(null));
    }

    /** 事项详情，含处理时间线 */
    @GetMapping("/{eventId}")
    public ApiResponse<AlertEventResponse> detail(@PathVariable Long eventId) {
        return ApiResponse.success("查询成功", queryService.detail(eventId));
    }

    /**
     * 老人主动提交求助。
     *
     * <p>归属从会话取，不接受前端传 elderId——否则可替他人求助。
     */
    @PostMapping("/help-calls")
    public ApiResponse<AlertEventResponse> createHelpCall(
            @Valid @RequestBody CreateHelpCallRequest request) {
        Long elderId = elderProfileService.ownElderId();
        AlertEvent event = eventService.createHelpCall(
                elderId,
                request.callType(),
                request.contactPhone(),
                request.description(),
                request.requestId(),
                null,
                LoginContext.requireUserId());
        return ApiResponse.success("已提交", queryService.toResponse(event));
    }

    /** 管理员认领 */
    @PostMapping("/{eventId}/claim")
    public ApiResponse<AlertEventResponse> claim(@PathVariable Long eventId,
                                                 @RequestParam(required = false) Integer version) {
        return ApiResponse.success("已认领", queryService.toResponse(handlingService.claim(eventId, version)));
    }

    /** 管理员分派 */
    @PostMapping("/{eventId}/assign")
    public ApiResponse<AlertEventResponse> assign(@PathVariable Long eventId,
                                                 @RequestParam Long assigneeId,
                                                 @RequestParam(required = false) String reason,
                                                 @RequestParam(required = false) Integer version) {
        return ApiResponse.success("已分派",
                queryService.toResponse(handlingService.assign(eventId, assigneeId, reason, version)));
    }

    /** 受派人接收 */
    @PostMapping("/{eventId}/accept")
    public ApiResponse<AlertEventResponse> accept(@PathVariable Long eventId,
                                                 @RequestParam(required = false) Integer version) {
        return ApiResponse.success("已接收",
                queryService.toResponse(handlingService.accept(eventId, version)));
    }

    /** 管理员转派 */
    @PostMapping("/{eventId}/transfer")
    public ApiResponse<AlertEventResponse> transfer(@PathVariable Long eventId,
                                                   @RequestParam Long newHandlerId,
                                                   @RequestParam String reason,
                                                 @RequestParam(required = false) Integer version) {
        return ApiResponse.success("已转派",
                queryService.toResponse(handlingService.transfer(eventId, newHandlerId, reason, version)));
    }

    /** 关闭：必须给结果与说明 */
    @PostMapping("/{eventId}/close")
    public ApiResponse<AlertEventResponse> close(@PathVariable Long eventId,
                                                @RequestParam String result,
                                                @RequestParam String note,
                                                 @RequestParam(required = false) Integer version) {
        CloseResult closeResult = CloseResult.from(result);
        return ApiResponse.success("已关闭",
                queryService.toResponse(handlingService.close(eventId, closeResult, note, version)));
    }

    /** 撤销：仅未分派的主动求助 */
    @PostMapping("/{eventId}/cancel")
    public ApiResponse<AlertEventResponse> cancel(@PathVariable Long eventId,
                                                 @RequestParam String reason,
                                                 @RequestParam(required = false) Integer version) {
        return ApiResponse.success("已撤销",
                queryService.toResponse(handlingService.cancel(eventId, reason, version)));
    }
}
