package org.imnu.smartcommunitycare.order.service;

import org.imnu.smartcommunitycare.catalog.mapper.ServiceItemMapper;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.catalog.service.CatalogService;
import org.imnu.smartcommunitycare.common.api.PageQuery;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderLogMapper;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrderLog;
import org.imnu.smartcommunitycare.order.model.response.OrderDetailResponse;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrderLog;
import org.imnu.smartcommunitycare.order.model.enums.OrderStatus;
import org.imnu.smartcommunitycare.order.model.request.CreateOrderRequest;
import org.imnu.smartcommunitycare.order.model.request.VersionGuard;
import org.imnu.smartcommunitycare.user.mapper.SysUserMapper;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 服务预约的创建与状态流转。
 *
 * <p><b>锁顺序固定为：服务项目 → 时段 → 订单。</b>
 * 创建预约要同时读服务与时段，流转要读订单；统一成这个顺序后，
 * 两个事务以相同顺序持锁，不会互相等待形成死锁。
 *
 * <p><b>名额规则</b>：创建 +1，只有首次合法取消 -1；完成与终止不减
 * （历史时段的名额仍算被用过）。扣减用 {@code ServiceSlotMapper} 的条件更新完成，
 * 不在这里"先查再改"。
 */
@Service
@RequiredArgsConstructor
public class OrderBookingService {

    private final ServiceOrderMapper orderMapper;
    private final ServiceOrderLogMapper orderLogMapper;
    private final OrderCreator orderCreator;
    private final OrderRequestLookup requestLookup;
    private final ServiceSlotMapper slotMapper;
    private final ServiceItemMapper itemMapper;
    private final ElderProfileMapper elderMapper;
    private final CatalogService catalogService;

    /** 只用于详情页取责任人姓名，不返回用户对象本身 */
    private final SysUserMapper userMapper;
    private final Clock clock;

    /**
     * 创建预约：扣名额 + 建单 + 写日志，同一事务。
     *
     * <p>顺序刻意是「先查防重键，再扣名额」：网络重试用同一个 requestId 时，
     * 直接返回原订单，不会二次扣名额。
     *
     * <p><b>并发同键的处理</b>：多个请求带同一个 requestId 同时进来时，
     * 它们可能都查不到已有订单（还没人插完），于是都往下走并同时扣名额，
     * 最后只有一个 insert 成功，其余撞唯一键 {@code uk_service_order_creator_request}
     * 抛 {@link DuplicateKeyException}。这不是异常情况，而是「同一次提交被重试」，
     * 应当返回同一条订单。所以捕获它、回滚本次事务、再回查原订单。
     */
    public ServiceOrder create(CreateOrderRequest request) {
        Long elderId = requireElderId();
        LocalDateTime now = LocalDateTime.now(clock);

        String requestHash = hash(
                request.slotId() + "|" + request.contactName() + "|"
                        + request.contactPhone() + "|" + nullToEmpty(request.address()));

        // 1. 防重：同一次提交重试，返回原订单，不重复占用名额
        ServiceOrder existing = orderMapper.findByRequest(elderId, request.requestId());
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw BusinessException.conflict("同一请求标识下内容不一致，请刷新后重试");
            }
            return existing;
        }

        try {
            return orderCreator.createInTransaction(elderId, request, requestHash, now);
        } catch (DuplicateKeyException e) {
            // 同键并发：唯一约束挡住了第二次插入。
            // createInTransaction 的事务已整体回滚，名额不会多扣。
            return resolveConcurrentRetry(elderId, request, requestHash,
                    "并发提交冲突，请刷新后重试");
        } catch (BusinessException e) {
            // 容量已满等业务失败（容量竞争）。
            //
            // 这里同样需要回查：<b>发起这一次的请求可能早就被别人抢在前面成功建单了</b>。
            // 典型交错（容量 1、同一 requestId 并发）：
            //   T1 前置查不到订单 → T2 前置查也看不到 → T1 提交占满名额并建单
            //   → T2 拿到时段行锁后 occupySlot 返回 0 → 抛「名额已满」
            // 此时 T2 面对的其实是「我那笔到底成没成」的问题，
            // 回查到原订单才是它要的答复；直接抛 409 名额已满会让用户
            // 以为自己的预约失败了，而实际上它已经成功。
            //
            // 只有回查不到原订单时，才说明这一笔确实没成功，原样抛出。
            if (!isSlotFull(e)) {
                throw e;
            }
            return resolveConcurrentRetry(elderId, request, requestHash, e.getUserMessage());
        }
    }

    /**
     * 并发后的收尾：回查原订单，查到就返回它，查不到就抛出原失败原因。
     *
     * <p>回查必须在独立事务里（{@link OrderRequestLookup}）：
     * 触发回查的这次事务已经因异常整体回滚，
     * 在同一个已回滚的事务里读会读到不该看到的状态。
     *
     * @param failureMessage 回查不到时的失败提示
     * @return 原订单；查不到时抛异常而非返回 null
     */
    private ServiceOrder resolveConcurrentRetry(Long elderId, CreateOrderRequest request,
                                               String requestHash, String failureMessage) {
        ServiceOrder winner = requestLookup
                .findExistingForRetry(elderId, request.requestId(), requestHash);
        if (winner != null) {
            return winner;
        }
        throw BusinessException.conflict(failureMessage);
    }

    /**
     * 使用409状态及“名额已满”消息识别容量冲突。
     * 409同时用于其他冲突，调整容量错误文案时需同步修改此判断。
     */
    private boolean isSlotFull(BusinessException e) {
        return e.getCode() == 409
                && e.getUserMessage() != null && e.getUserMessage().contains("名额已满");
    }

    /**
     * 取消预约。
     *
     * <p>只有<b>首次</b>合法取消才释放名额：状态条件更新保证重复取消不会二次减。
     * 老人须在时段开始前取消；已开始的服务不能走这条路。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder cancel(Long orderId, String reason, Integer clientVersion) {
        ServiceOrder order = requireOwnedOrder(orderId);
        OrderStatus current = OrderStatus.from(order.getStatus());

        if (!current.isCancellable()) {
            throw BusinessException.conflict("当前状态「" + current.label() + "」不允许取消");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!order.getSlotStartSnapshot().isAfter(now)) {
            throw BusinessException.conflict("服务时段已开始，无法取消");
        }

        int updated = orderMapper.compareAndSetStatus(
                orderId, current.name(), OrderStatus.CANCELLED.name(), VersionGuard.resolve(clientVersion, order, "取消"));
        if (updated != 1) {
            throw BusinessException.conflict("订单状态已变化，请刷新后重试");
        }

        order.setStatus(OrderStatus.CANCELLED.name());
        // 状态条件更新已推进版本；实体补写须保持新版本，不能覆盖成旧值。
        order.setVersion(order.getVersion() + 1);
        order.setCancelReason(reason);
        order.setCancelledAt(now);
        order.setUpdatedAt(now);
        orderMapper.updateById(order);

        // 只在首次取消时释放名额
        slotMapper.releaseSlot(order.getSlotId());

        writeLog(orderId, "CANCEL", current.name(), OrderStatus.CANCELLED.name(), reason);
        return order;
    }

    /**
     * 管理员视角的订单列表，服务端分页。
     *
     * <p>分页在数据库侧完成：订单会持续增长，一次拉全表再让浏览器过滤
     * 既慢又可能因数据量增长而崩页面。
     */
    public PageQuery.PageResult<ServiceOrder> listForAdmin(PageQuery pageQuery, String status) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        PageQuery query = pageQuery == null ? new PageQuery() : pageQuery;

        List<ServiceOrder> rows = orderMapper.pageForAdmin(
                blankToNull(status), query.safePageSize(), query.offset());
        long total = orderMapper.countForAdmin(blankToNull(status));

        return PageQuery.PageResult.of(rows, total, query.safePageNum(), query.safePageSize());
    }

    /**
     * 订单处理时间线。
     *
     * <p>调用方需先完成对象级授权（本方法不做权限判断），
     * 避免「查时间线」成为绕过详情授权的旁路。
     */
    public List<ServiceOrderLog> listLogs(Long orderId) {
        return orderMapper.findLogsByOrder(orderId);
    }

    /**
     * 分页查询当前会话老人本人的订单，老人ID从会话档案取得。
     * 保留原全量查询接口的响应契约。
     */
    public PageQuery.PageResult<ServiceOrder> listMinePaged(PageQuery pageQuery, String status) {
        PageQuery query = pageQuery == null ? new PageQuery() : pageQuery;
        Long elderId = requireElderId();
        String filter = blankToNull(status);

        List<ServiceOrder> rows = orderMapper.pageByElder(
                elderId, filter, query.safePageSize(), query.offset());
        long total = orderMapper.countByElder(elderId, filter);

        // 页码超出范围仍返回真实total，供页面恢复到有效页。
        return PageQuery.PageResult.of(rows, total, query.safePageNum(), query.safePageSize());
    }

    /**
     * 分页查询当前会话老人本人的订单，老人ID从会话档案取得。
     * 保留原全量查询接口的响应契约。
     */
    public PageQuery.PageResult<ServiceOrder> listAssignedToMePaged(PageQuery pageQuery, String status) {
        PageQuery query = pageQuery == null ? new PageQuery() : pageQuery;
        Long userId = LoginContext.requireUserId();
        String filter = blankToNull(status);

        List<ServiceOrder> rows = orderMapper.pageByStaff(
                userId, filter, query.safePageSize(), query.offset());
        long total = orderMapper.countByStaff(userId, filter);

        // 同上：空页也保留真实 total，不用 PageResult.empty()。
        return PageQuery.PageResult.of(rows, total, query.safePageNum(), query.safePageSize());
    }

    /** 我的订单（老人）——不分页的全量版本，保留供内部逻辑使用 */
    public List<ServiceOrder> listMine() {
        return orderMapper.findByElder(requireElderId());
    }

    /** 分派给我的订单（服务人员）——不分页的全量版本，保留供内部逻辑使用 */
    public List<ServiceOrder> listAssignedToMe() {
        Long userId = LoginContext.requireUserId();
        return orderMapper.findByStaff(userId);
    }

    /** 查详情：老人只看自己的，服务人员只看分派给自己的，管理员看全部 */
    public ServiceOrder detail(Long orderId) {
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("订单不存在");
        }
        UserRole role = LoginContext.requireRole();
        if (role == UserRole.COMMUNITY_ADMIN) {
            return order;
        }
        if (role == UserRole.ELDER) {
            if (!order.getElderId().equals(requireElderId())) {
                // 不区分「不存在」与「无权」，避免泄露订单是否存在
                throw BusinessException.notFound("订单不存在");
            }
            return order;
        }
        // 未分派订单的 staffId 为 null，仍按无权访问返回 404。
        if (!LoginContext.requireUserId().equals(order.getStaffId())) {
            throw BusinessException.notFound("订单不存在");
        }
        return order;
    }

    /**
     * 读取订单页面安全详情，复用detail的对象权限校验。
     * 补充当前责任人姓名，不返回请求键或摘要；ID统一转为字符串。
     */
    public OrderDetailResponse detailView(Long orderId) {
        // 授权交给 detail：它已经把三种角色的边界都判完了
        ServiceOrder order = detail(orderId);

        String staffId = null;
        String staffName = null;
        if (order.getStaffId() != null) {
            staffId = String.valueOf(order.getStaffId());
            // 只取姓名。取整个用户对象会把手机号、邮箱、备注一起带出去，
            // 详情页并不需要那些。
            SysUser staff = userMapper.selectById(order.getStaffId());
            if (staff != null) {
                staffName = staff.getRealName();
            }
            // 查不到用户时 staffName 保持 null，页面显示「未知」而不是空白
        }

        return new OrderDetailResponse(
                String.valueOf(order.getId()),
                order.getOrderNo(),
                order.getStatus(),
                order.getServiceNameSnapshot(),
                order.getPriceSnapshot(),
                order.getSlotStartSnapshot(),
                order.getSlotEndSnapshot(),
                order.getContactName(),
                order.getContactPhone(),
                order.getAddressSnapshot(),
                staffId,
                staffName,
                order.getServiceResult(),
                order.getStartedAt(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getCancelReason(),
                order.getVersion(),
                order.getCreatedAt());
    }

    // ---------------- 内部方法 ----------------

    private ServiceOrder requireOwnedOrder(Long orderId) {
        ServiceOrder order = detail(orderId);
        OrderStatus.from(order.getStatus()); // 未知状态直接拒绝
        return order;
    }

    /** 当前登录者对应的老人档案 ID */
    private Long requireElderId() {
        Long userId = LoginContext.requireUserId();
        if (LoginContext.requireRole() != UserRole.ELDER) {
            throw BusinessException.forbidden("仅老人可以创建或取消预约");
        }
        ElderProfile profile = elderMapper.findByUserId(userId);
        if (profile == null) {
            throw BusinessException.badRequest("当前账号尚未建立老人档案");
        }
        return profile.getId();
    }

    private void writeLog(Long orderId, String action, String from, String to, String reason) {
        ServiceOrderLog log = new ServiceOrderLog();
        log.setOrderId(orderId);
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorId(LoginContext.isLoggedIn() ? LoginContext.requireUserId() : null);
        log.setOperatorSource("USER");
        log.setReason(reason);
        log.setCreatedAt(LocalDateTime.now(clock));
        orderLogMapper.insert(log);
    }

    /** 订单号：SO + 时间戳 + 4 位随机，演示规模下够用且可读 */
    private String generateOrderNo(LocalDateTime now) {
        return "SO" + now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + (int) (Math.random() * 9000 + 1000);
    }

    private String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("摘要算法不可用", e);
        }
    }

    /** 空字符串按 null 处理：查询条件里「空」与「不过滤」含义相同 */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
