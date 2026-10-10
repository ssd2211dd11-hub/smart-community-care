package org.imnu.smartcommunitycare.order.model.enums;

import org.imnu.smartcommunitycare.common.exception.BusinessException;

import java.util.Set;

/**
 * 订单状态。
 *
 * <p>状态机（只允许这些迁移，其余一律拒绝）：
 * <pre>
 *   无 → PENDING_ASSIGNMENT      预约
 *   PENDING_ASSIGNMENT → ASSIGNED            分派
 *   PENDING_ASSIGNMENT → ACCEPTED            人员自主领取并接单
 *   ASSIGNED → ACCEPTED                     接单
 *   ASSIGNED → PENDING_ASSIGNMENT           拒接
 *   ASSIGNED / ACCEPTED → ASSIGNED          转派
 *   ACCEPTED → IN_SERVICE                   开始服务
 *   IN_SERVICE → PENDING_CONFIRMATION       提交结果
 *   PENDING_CONFIRMATION → COMPLETED         老人确认
 *   PENDING_CONFIRMATION → IN_SERVICE       退回重做
 *   PENDING_ASSIGNMENT / ASSIGNED / ACCEPTED → CANCELLED   取消
 *   IN_SERVICE / PENDING_CONFIRMATION → TERMINATED          异常终止
 * </pre>
 *
 * <p>{@code COMPLETED} / {@code CANCELLED} / {@code TERMINATED} 是终态，不可回退。
 *
 * <p><b>名额规则</b>（V1.2 §4.6.3）：创建时 +1，<b>只有首次合法取消时 -1</b>；
 * 完成与终止不减名额——历史时段的名额仍算被用过。
 */
public enum OrderStatus {

    PENDING_ASSIGNMENT("待领取 / 待分派"),
    ASSIGNED("待接单"),
    ACCEPTED("待服务"),
    IN_SERVICE("服务中"),
    PENDING_CONFIRMATION("待确认"),
    COMPLETED("已完成"),
    CANCELLED("已取消"),
    TERMINATED("已终止");

    private final String label;

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isFinal() {
        return this == COMPLETED || this == CANCELLED || this == TERMINATED;
    }

    /** 服务开始前的三个状态，可以直接取消 */
    public boolean isCancellable() {
        return this == PENDING_ASSIGNMENT || this == ASSIGNED || this == ACCEPTED;
    }

    public boolean canMoveTo(OrderStatus target) {
        return switch (this) {
            case PENDING_ASSIGNMENT -> target == ASSIGNED || target == ACCEPTED || target == CANCELLED;
            case ASSIGNED -> target == ACCEPTED
                    || target == PENDING_ASSIGNMENT   // 拒接
                    || target == CANCELLED;
            case ACCEPTED -> target == ASSIGNED        // 转派
                    || target == IN_SERVICE
                    || target == CANCELLED;
            case IN_SERVICE -> target == PENDING_CONFIRMATION || target == TERMINATED;
            case PENDING_CONFIRMATION -> target == COMPLETED
                    || target == IN_SERVICE            // 退回重做
                    || target == TERMINATED;
            case COMPLETED, CANCELLED, TERMINATED -> false;
        };
    }

    public void requireCanMoveTo(OrderStatus target, String action) {
        if (!canMoveTo(target)) {
            throw BusinessException.conflict(
                    "当前状态「" + label + "」不允许执行「" + action + "」");
        }
    }

    public static OrderStatus from(String value) {
        for (OrderStatus s : values()) {
            if (s.name().equals(value)) {
                return s;
            }
        }
        throw BusinessException.badRequest("未知的订单状态：" + value);
    }

    public static Set<OrderStatus> finalStates() {
        return Set.of(COMPLETED, CANCELLED, TERMINATED);
    }
}
