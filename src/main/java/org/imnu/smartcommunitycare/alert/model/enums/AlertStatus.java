package org.imnu.smartcommunitycare.alert.model.enums;

import org.imnu.smartcommunitycare.common.exception.BusinessException;

import java.util.Set;

/**
 * 事件状态。
 *
 * <p>状态机（只允许这些迁移，其余一律拒绝）：
 * <pre>
 *   无 → PENDING            创建（老人求助 或 规则命中）
 *   PENDING → PROCESSING    管理员直接认领
 *   PENDING → ASSIGNED       分派给指定处理人
 *   ASSIGNED → PROCESSING    受派人接收
 *   ASSIGNED/PROCESSING → ASSIGNED   转派（管理员）
 *   PROCESSING → CLOSED     关闭（当前责任人或管理员）
 *   PENDING → CANCELLED     撤销（仅主动求助）
 * </pre>
 *
 * <p>{@code CLOSED} 与 {@code CANCELLED} 是终态，不重新打开。
 */
public enum AlertStatus {

    PENDING("待处理"),
    ASSIGNED("待接收"),
    PROCESSING("处理中"),
    CLOSED("已关闭"),
    CANCELLED("已撤销");

    private final String label;

    AlertStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 未结状态：待办只算这三种，CLOSED 与 CANCELLED 不计入 */
    public boolean isOpen() {
        return this == PENDING || this == ASSIGNED || this == PROCESSING;
    }

    public boolean isFinal() {
        return this == CLOSED || this == CANCELLED;
    }

    /** 当前状态是否允许迁移到目标状态 */
    public boolean canMoveTo(AlertStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING || target == ASSIGNED || target == CANCELLED;
            case ASSIGNED -> target == PROCESSING || target == ASSIGNED;
            case PROCESSING -> target == CLOSED || target == ASSIGNED;
            case CLOSED, CANCELLED -> false;
        };
    }

    /** 校验迁移合法，非法时给出明确原因 */
    public void requireCanMoveTo(AlertStatus target, String action) {
        if (!canMoveTo(target)) {
            throw BusinessException.conflict(
                    "当前状态「" + label + "」不允许执行「" + action + "」");
        }
    }

    public static AlertStatus from(String value) {
        for (AlertStatus s : values()) {
            if (s.name().equals(value)) {
                return s;
            }
        }
        throw BusinessException.badRequest("未知的事件状态：" + value);
    }

    public static Set<AlertStatus> openStates() {
        return Set.of(PENDING, ASSIGNED, PROCESSING);
    }
}
