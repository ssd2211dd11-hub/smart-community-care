package org.imnu.smartcommunitycare.alert.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 事件响应。
 *
 * <p>标 {@code JsonInclude(ALWAYS)}：全局配置了 {@code non_null}，
 * 会把 null 字段整个省略；前端需要区分"逾期时间为空"和"没这个字段"。
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AlertEventResponse(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        String eventNo,
        String sourceType,
        String sourceLabel,
        String ruleCode,
        @JsonSerialize(using = ToStringSerializer.class) Long elderId,
        String elderName,
        String status,
        String statusLabel,
        String priority,
        @JsonSerialize(using = ToStringSerializer.class) Long handlerId,
        String handlerName,
        Integer occurrenceCount,
        LocalDateTime responseDueAt,
        LocalDateTime firstAcceptedAt,
        LocalDateTime closedAt,
        String closeResult,
        String closeNote,
        String callType,
        String contactPhone,
        String description,
        Integer version,
        /** 是否首次响应逾期，由后端按截止时间计算 */
        boolean firstResponseOverdue,
        LocalDateTime createdAt,
        List<LogEntry> timeline
) {

    /** 时间线条目 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LogEntry(
            String action,
            String fromStatus,
            String toStatus,
            String operatorName,
            String operatorSource,
            String reason,
            LocalDateTime createdAt
    ) {
    }
}
