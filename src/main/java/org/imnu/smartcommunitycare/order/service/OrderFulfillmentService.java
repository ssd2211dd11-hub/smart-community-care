package org.imnu.smartcommunitycare.order.service;

import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.api.PageQuery;
import org.imnu.smartcommunitycare.order.model.response.ClaimableOrderResponse;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderLogMapper;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.response.MyParticipationResponse;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrderLog;
import org.imnu.smartcommunitycare.order.model.enums.OrderStatus;
import org.imnu.smartcommunitycare.order.model.request.VersionGuard;
import org.imnu.smartcommunitycare.order.model.request.AssignStaffRequest;
import org.imnu.smartcommunitycare.order.model.request.OrderActionRequest;
import org.imnu.smartcommunitycare.order.model.request.TransferOrderRequest;
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
 * 订单分派与履约动作。
 *
 * <p><b>动作与名额无关</b>：分派、接单、拒接、转派、开工、提交、确认、
 * 退回、终止都不改变名额。名额只在创建（+1）与首次合法取消（-1）时变化。
 *
 * <p><b>事务边界</b>：每个公开方法是一个事务，状态、关联字段、版本与
 * 必要日志原子提交。SQL 都带状态与版本条件，影响行数 0 即表示
 * 有人抢先改了状态，返回冲突而不是覆盖。
 *
 * <p>加锁顺序：订单自身动作只锁订单行，不回头锁时段或服务。
 * 动订单不需要重读时段，锁多了只会拉长事务、增加死锁面。
 */
@Service
@RequiredArgsConstructor
public class OrderFulfillmentService {

    private final ServiceOrderMapper orderMapper;
    private final ServiceOrderLogMapper orderLogMapper;
    private final SysUserMapper userMapper;
    private final Clock clock;

    // ---------------- 分派与交接 ----------------

    public PageQuery.PageResult<ClaimableOrderResponse> claimablePage(PageQuery query) {
        Long staffId = requireCurrentStaff();
        requireAssignableStaff(staffId);
        return PageQuery.PageResult.of(
                orderMapper.claimablePage(query.safePageSize(), query.offset()), orderMapper.countClaimable(),
                query.safePageNum(), query.safePageSize());
    }

    /** 自主领取即本人确认接单；锁订单并保留SQL状态/版本条件，不能抢走已受派订单。 */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder claim(Long orderId, Integer version) {
        Long staffId = requireCurrentStaff();
        requireAssignableStaff(staffId);
        ServiceOrder order = orderMapper.findByIdForUpdate(orderId);
        if (order == null) throw BusinessException.notFound("订单不存在");
        if (!OrderStatus.PENDING_ASSIGNMENT.name().equals(order.getStatus()) || order.getStaffId() != null) {
            throw BusinessException.conflict("该任务已被领取或处理，请刷新待领取列表");
        }
        OrderStatus.from(order.getStatus()).requireCanMoveTo(OrderStatus.ACCEPTED, "自主领取");
        int updated = orderMapper.claimByStaff(orderId, staffId, VersionGuard.resolve(version, order, "领取"),
                LocalDateTime.now(clock));
        if (updated != 1) throw BusinessException.conflict("领取失败：任务已被其他人员处理，请刷新");
        orderLogMapper.insert(logOf(orderId, "CLAIM", order.getStatus(), OrderStatus.ACCEPTED.name(), "服务人员自主领取并接单"));
        return orderMapper.selectById(orderId);
    }

    /**
     * 管理员分派订单给服务人员。
     *
     * <p>目标必须是<b>启用的服务人员</b>：停用人员接不了单，
     * 分派给他等于让订单卡死。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder assign(Long orderId, AssignStaffRequest request) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        ServiceOrder order = requireOrder(orderId);
        requireAssignableStaff(request.staffId());

        OrderStatus.from(order.getStatus())
                .requireCanMoveTo(OrderStatus.ASSIGNED, "分派");

        int updated = orderMapper.assignStaff(orderId, request.staffId(),
                VersionGuard.resolve(request.version(), order, "分派"));
        if (updated != 1) {
            throw BusinessException.conflict("订单状态已变化，请刷新后重试");
        }

        String reason = "分派给 " + staffName(request.staffId())
                + (isBlank(request.reason()) ? "" : "：" + request.reason());
        orderLogMapper.insert(logOf(orderId, "ASSIGN",
                order.getStatus(), OrderStatus.ASSIGNED.name(), reason));

        return orderMapper.selectById(orderId);
    }

    /**
     * 服务人员接单。
     *
     * <p>只有<b>当前受派人</b>能接。SQL 里带 {@code staff_id = 当前用户} 条件，
     * 不是先查后比，避免两人同时操作时的时序问题。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder accept(Long orderId, OrderActionRequest request) {
        Long userId = requireCurrentStaff();
        ServiceOrder order = requireAssignedOrder(orderId, userId);
        OrderStatus.from(order.getStatus())
                .requireCanMoveTo(OrderStatus.ACCEPTED, "接单");

        int updated = orderMapper.acceptByStaff(
                orderId, userId, VersionGuard.resolve(request.version(), order, "接单"),
                LocalDateTime.now(clock));
        if (updated != 1) {
            throw BusinessException.conflict("接单失败：订单已被处理或不属于当前人员");
        }

        orderLogMapper.insert(logOf(orderId, "ACCEPT",
                order.getStatus(), OrderStatus.ACCEPTED.name(), request.reason()));

        return orderMapper.selectById(orderId);
    }

    /**
     * 服务人员拒接，回到待分派。
     *
     * <p>清空受派人与本轮接单时间，但<b>历史保留在日志里</b>：
     * 谁拒接过、为什么，要能查得到。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder reject(Long orderId, OrderActionRequest request) {
        Long userId = requireCurrentStaff();
        ServiceOrder order = requireAssignedOrder(orderId, userId);
        OrderStatus.from(order.getStatus())
                .requireCanMoveTo(OrderStatus.PENDING_ASSIGNMENT, "拒接");

        int updated = orderMapper.rejectByStaff(orderId, userId,
                VersionGuard.resolve(request.version(), order, "拒接"));
        if (updated != 1) {
            throw BusinessException.conflict("拒接失败：订单已被处理或不属于当前人员");
        }

        orderLogMapper.insert(logOf(orderId, "REJECT",
                order.getStatus(), OrderStatus.PENDING_ASSIGNMENT.name(), request.reason()));

        return orderMapper.selectById(orderId);
    }

    /**
     * 管理员转派给另一名服务人员。
     *
     * <p>从 ASSIGNED 或 ACCEPTED 均可转出。转派后<b>新人员需要重新接单</b>——
     * 上一个人接受了不代表新的人接受了。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder transfer(Long orderId, TransferOrderRequest request) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        ServiceOrder order = requireOrder(orderId);
        requireAssignableStaff(request.newStaffId());

        // 先判业务重复：转派给自己是无意义操作，属于参数问题（400），
        // 放在状态检查之前，否则 ASSIGNED → ASSIGNED 在状态机里是合法的，
        // 会先撞上 409，掩盖真正的问题
        if (request.newStaffId().equals(order.getStaffId())) {
            throw BusinessException.badRequest("不能转派给当前已受派的人员");
        }

        OrderStatus current = OrderStatus.from(order.getStatus());
        // 未接单和已接单均可转派；ASSIGNED 的换人由本动作明确处理。
        if (current != OrderStatus.ASSIGNED && current != OrderStatus.ACCEPTED) {
            throw BusinessException.conflict("当前状态「" + current.label() + "」不允许转派");
        }

        int updated = orderMapper.transferStaff(
                orderId, request.newStaffId(),
                VersionGuard.resolve(request.version(), order, "转派"));
        if (updated != 1) {
            throw BusinessException.conflict("转派失败：订单状态已变化，请刷新后重试");
        }

        String from = order.getStaffId() == null ? "未分派" : staffName(order.getStaffId());
        orderLogMapper.insert(logOf(orderId, "TRANSFER",
                current.name(), OrderStatus.ASSIGNED.name(),
                "由 " + from + " 转派给 " + staffName(request.newStaffId())
                        + "：" + request.reason()));

        return orderMapper.selectById(orderId);
    }

    // ---------------- 服务人员的任务列表 ----------------

    /** 分派给我的订单（不区分状态，页面按状态分组展示） */
    public List<ServiceOrder> listMyTasks() {
        // 必须先校验角色：否则老人用自己的账号也能查这张表，
        // 只要库里存在 staff_id 等于他ID 的订单就会泄露。
        // 正常流程不会有这种数据，但一旦有脏数据就等于越权。
        requireCurrentStaff();
        return orderMapper.findByStaff(LoginContext.requireUserId());
    }

    // ---------------- 服务人员的参与历史----------------

    /**
     * 我参与过的订单摘要。
     *
     * <p><b>与 {@link #listMyTasks()} 的区别</b>：那边按<b>当前</b> {@code staff_id} 查，
     * 订单一转派就从列表消失；这边按<b>日志里的 {@code operator_id}</b> 查，
     * 记录的是「我确实做过什么」，<b>转派之后依然在</b>。
     *
     * <p>角色校验与任务列表一致：不能因为换了查询入口就放过老人或管理员。
     *
     * @param limit 最多返回多少条，防止历史累积后一次拉全量
     */
    public List<MyParticipationResponse> listMyParticipations(int limit) {
        Long staffId = requireCurrentStaff();
        int capped = limit <= 0 ? 20 : Math.min(limit, 100);
        return orderLogMapper.findMyParticipations(staffId, capped);
    }
    @Transactional(readOnly=true)
    public org.imnu.smartcommunitycare.common.api.PageQuery.PageResult<MyParticipationResponse> participationPage(
            org.imnu.smartcommunitycare.common.api.PageQuery query) {
        Long staff=requireCurrentStaff();
        return org.imnu.smartcommunitycare.common.api.PageQuery.PageResult.of(orderLogMapper.participationPage(staff,query.safePageSize(),query.offset()),
                orderLogMapper.participationCount(staff),query.safePageNum(),query.safePageSize());
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

    /** 当前操作者必须是服务人员角色 */
    private Long requireCurrentStaff() {
        LoginContext.requireAnyRole(UserRole.SERVICE_STAFF);
        return LoginContext.requireUserId();
    }

    /**
     * 校验目标账号能否被分派。
     *
     * <p>必须是启用状态的服务人员。管理员和老人都不能作为订单受派人。
     */
    private void requireAssignableStaff(Long staffId) {
        SysUser target = userMapper.selectById(staffId);
        if (target == null) {
            throw BusinessException.badRequest("所选人员不存在");
        }
        if (!UserRole.SERVICE_STAFF.name().equals(target.getRole())) {
            throw BusinessException.badRequest("只能分派给服务人员");
        }
        if (!Boolean.TRUE.equals(target.getEnabled())) {
            throw BusinessException.badRequest("该服务人员已停用，不能接收新任务");
        }
    }

    private String staffName(Long staffId) {
        SysUser user = userMapper.selectById(staffId);
        return user == null ? ("#" + staffId) : user.getRealName();
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

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
