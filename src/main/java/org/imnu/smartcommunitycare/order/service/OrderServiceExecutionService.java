package org.imnu.smartcommunitycare.order.service;

import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderLogMapper;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrderLog;
import org.imnu.smartcommunitycare.order.model.enums.OrderStatus;
import org.imnu.smartcommunitycare.order.model.request.VersionGuard;
import org.imnu.smartcommunitycare.order.model.request.ConfirmOrderRequest;
import org.imnu.smartcommunitycare.order.model.request.SubmitResultRequest;
import org.imnu.smartcommunitycare.order.model.request.TerminateOrderRequest;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 履约后半程：开工、提交结果、确认、退回与异常终止。
 *
 * <p><b>名额不变</b>：这五个动作都不影响名额。服务一旦开始，名额就算用过；
 * 未能完成走异常终止而不是取消，因为服务确实发生了。
 *
 * <p><b>时间规则</b>：开工不得早于预约开始时间。这条在 Service 里用注入的
 * {@link Clock} 判断，测试可注入固定时钟验证边界，不依赖机器当前时间。
 *
 * <p><b>过程留痕</b>：退回重做不重置 started_at，每次提交与退回都追加日志。
 * 多次来回的历史都保留，不用最新结果盖掉前面发生了什么。
 */
@Service
@RequiredArgsConstructor
public class OrderServiceExecutionService {

    private final ServiceOrderMapper orderMapper;
    private final ServiceOrderLogMapper orderLogMapper;
    private final ElderProfileMapper elderMapper;
    private final Clock clock;

    /**
     * 开始服务。
     *
     * <p>只有当前受派人可开工，且不得早于预约开始时间。
     * 早于开始时间返回 400 并说明原因，不静默放行。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder start(Long orderId, Integer clientVersion) {
        Long userId = requireCurrentStaff();
        ServiceOrder order = requireAssignedOrder(orderId, userId);

        OrderStatus.from(order.getStatus()).requireCanMoveTo(OrderStatus.IN_SERVICE, "开始服务");

        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(order.getSlotStartSnapshot())) {
            throw BusinessException.badRequest(
                    "服务时段尚未开始，不能提前开工（开始时间 " + order.getSlotStartSnapshot() + "）");
        }

        // 页面读到的版本参与乐观锁：与 submitResult 用同一个 VersionGuard。
        // 原先这里固定用 order.getVersion()，等于「服务端刚读出来的当前值」，
        // 并发下第二个请求照样成功——缺版本就绕过了竞争检查。
        int updated = orderMapper.startService(
                orderId, userId, VersionGuard.resolve(clientVersion, order, "开工"), now);
        if (updated != 1) {
            throw BusinessException.conflict("开工失败：订单已被处理或不属于当前人员");
        }

        orderLogMapper.insert(logOf(orderId, "START",
                order.getStatus(), OrderStatus.IN_SERVICE.name(), "开始上门服务"));

        return orderMapper.selectById(orderId);
    }

    /**
     * 提交服务结果，进入待确认。
     *
     * <p>服务内容与结果必填——没有结果的「已完成」对老人没有意义。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder submitResult(Long orderId, SubmitResultRequest request) {
        Long userId = requireCurrentStaff();
        ServiceOrder order = requireAssignedOrder(orderId, userId);

        OrderStatus.from(order.getStatus())
                .requireCanMoveTo(OrderStatus.PENDING_CONFIRMATION, "提交结果");

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = orderMapper.submitResult(
                orderId, userId, VersionGuard.resolve(request.version(), order, "提交结果"),
                request.result(), now);
        if (updated != 1) {
            throw BusinessException.conflict("提交失败：订单已被处理或不属于当前人员");
        }

        orderLogMapper.insert(logOf(orderId, "SUBMIT",
                order.getStatus(), OrderStatus.PENDING_CONFIRMATION.name(), request.result()));

        return orderMapper.selectById(orderId);
    }

    /**
     * 老人确认完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder confirm(Long orderId, Integer clientVersion) {
        Long elderId = requireCurrentElder();
        ServiceOrder order = requireOrder(orderId);
        // 归属校验放在状态校验之前：否则他人订单会先撞上「状态不允许」返回 409，
        // 泄露该订单当前处于什么状态。无权访问应统一按 404 处理。
        if (!elderId.equals(order.getElderId())) {
            throw BusinessException.notFound("订单不存在");
        }

        OrderStatus.from(order.getStatus())
                .requireCanMoveTo(OrderStatus.COMPLETED, "确认完成");

        LocalDateTime now = LocalDateTime.now(clock);
        // 同 start：老人确认也接上版本通道，不用服务端刚读出的当前值
        int updated = orderMapper.confirmByElder(
                orderId, elderId, VersionGuard.resolve(clientVersion, order, "确认完成"), now);
        if (updated != 1) {
            throw BusinessException.conflict("确认失败：订单状态已变化或不属于本人");
        }

        orderLogMapper.insert(logOf(orderId, "CONFIRM",
                order.getStatus(), OrderStatus.COMPLETED.name(), "老人确认服务完成"));

        return orderMapper.selectById(orderId);
    }

    /**
     * 管理员代确认。
     *
     * <p><b>必须填写原因</b>：代确认意味着跳过了老人的确认环节，
     * 没有原因就无法追溯为什么绕过本人意愿。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder confirmByAdmin(Long orderId, ConfirmOrderRequest request) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        if (request.reason() == null || request.reason().isBlank()) {
            throw BusinessException.badRequest("管理员代确认必须填写原因");
        }
        ServiceOrder order = requireOrder(orderId);

        OrderStatus.from(order.getStatus())
                .requireCanMoveTo(OrderStatus.COMPLETED, "确认完成");

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = orderMapper.confirmByAdmin(orderId,
                VersionGuard.resolve(request.version(), order, "代确认"), now);
        if (updated != 1) {
            throw BusinessException.conflict("确认失败：订单状态已变化");
        }

        orderLogMapper.insert(logOf(orderId, "CONFIRM",
                order.getStatus(), OrderStatus.COMPLETED.name(),
                "管理员代确认：" + request.reason().trim()));

        return orderMapper.selectById(orderId);
    }

    /**
     * 老人反馈未完成事项，订单回到服务中。
     *
     * <p>不重置 started_at：服务确实已经发生过，退回不是「从未开工」。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder sendBack(Long orderId, SubmitResultRequest request) {
        ServiceOrder order = requireOrder(orderId);
        requireOwnOrder(order);

        OrderStatus.from(order.getStatus())
                .requireCanMoveTo(OrderStatus.IN_SERVICE, "反馈未完成");

        int updated = orderMapper.returnToService(orderId,
                VersionGuard.resolve(request.version(), order, "退回"));
        if (updated != 1) {
            throw BusinessException.conflict("操作失败：订单状态已变化");
        }

        orderLogMapper.insert(logOf(orderId, "RETURN",
                order.getStatus(), OrderStatus.IN_SERVICE.name(),
                "反馈未完成：" + request.result()));

        return orderMapper.selectById(orderId);
    }

    /**
     * 管理员异常终止。
     *
     * <p>原因必填。终止不是成功完成，名额不释放——服务已经发生。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder terminate(Long orderId, TerminateOrderRequest request) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        ServiceOrder order = requireOrder(orderId);

        OrderStatus current = OrderStatus.from(order.getStatus());
        if (!current.canMoveTo(OrderStatus.TERMINATED)) {
            throw BusinessException.conflict("当前状态「" + current.label() + "」不允许终止");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = orderMapper.terminateOrder(orderId,
                VersionGuard.resolve(request.version(), order, "异常终止"), now);
        if (updated != 1) {
            throw BusinessException.conflict("终止失败：订单状态已变化");
        }

        orderLogMapper.insert(logOf(orderId, "TERMINATE",
                current.name(), OrderStatus.TERMINATED.name(),
                "异常终止：" + request.reason()));

        return orderMapper.selectById(orderId);
    }

    // ---------------- 内部方法 ----------------

    /** 对象归属先于状态与版本反馈，SQL 仍保留 staff_id 条件防竞态。 */
    private ServiceOrder requireAssignedOrder(Long orderId, Long staffId) {
        ServiceOrder order = requireOrder(orderId);
        if (!staffId.equals(order.getStaffId())) {
            throw BusinessException.notFound("订单不存在");
        }
        return order;
    }

    private ServiceOrder requireOrder(Long orderId) {
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("订单不存在");
        }
        return order;
    }

    private Long requireCurrentStaff() {
        LoginContext.requireAnyRole(UserRole.SERVICE_STAFF);
        return LoginContext.requireUserId();
    }

    private Long requireCurrentElder() {
        LoginContext.requireAnyRole(UserRole.ELDER);
        Long userId = LoginContext.requireUserId();
        ElderProfile profile = elderMapper.findByUserId(userId);
        if (profile == null) {
            throw BusinessException.badRequest("当前账号尚未建立老人档案");
        }
        return profile.getId();
    }

    /** 老人只能操作自己的订单；无权时按 404 处理，不泄露订单是否存在 */
    private void requireOwnOrder(ServiceOrder order) {
        Long elderId = requireCurrentElder();
        if (!elderId.equals(order.getElderId())) {
            throw BusinessException.notFound("订单不存在");
        }
    }

    private ServiceOrderLog logOf(Long orderId, String action,
                                  String from, String to, String reason) {
        ServiceOrderLog log = new ServiceOrderLog();
        log.setOrderId(orderId);
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorId(LoginContext.isLoggedIn() ? LoginContext.requireUserId() : null);
        log.setOperatorSource("USER");
        log.setReason(reason);
        log.setCreatedAt(LocalDateTime.now(clock));
        return log;
    }
}
