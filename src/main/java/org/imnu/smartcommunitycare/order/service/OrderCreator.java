package org.imnu.smartcommunitycare.order.service;

import org.imnu.smartcommunitycare.catalog.mapper.ServiceItemMapper;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderLogMapper;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrderLog;
import org.imnu.smartcommunitycare.order.model.enums.OrderStatus;
import org.imnu.smartcommunitycare.order.model.request.CreateOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 订单落库的事务执行者。
 *
 * <p><b>为什么单独一个 Bean</b>：Spring 声明式事务基于代理，同类内部直接调用
 * 带 {@code @Transactional} 的方法不会经过代理，事务不会开启、异常也不会触发回滚。
 * 建单流程需要「失败必须整体回滚」（名额、订单、日志三者同生共死），
 * 所以把这段放进独立 Bean，由 {@code OrderBookingService} 通过代理调用。
 *
 * <p>加锁顺序固定为 service_item → service_slot → service_order，
 * 与其他资源相关写入保持一致，避免交叉持锁形成死锁。
 */
@Service
@RequiredArgsConstructor
public class OrderCreator {

    private final ServiceOrderMapper orderMapper;
    private final ServiceOrderLogMapper orderLogMapper;
    private final ServiceSlotMapper slotMapper;
    private final ServiceItemMapper itemMapper;
    private final Clock clock;

    /**
     * 在一个事务里完成「扣名额 + 建单 + 写日志」。
     *
     * <p><b>必须有这个注解</b>：三者必须同生共死。缺了它，唯一键冲突时
     * insert 失败但已扣的名额不会回滚，出现「订单没建成、名额却扣了」。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceOrder createInTransaction(Long elderId, CreateOrderRequest request,
                                           String requestHash, LocalDateTime now) {
        // 锁顺序：服务 → 时段，与目录管理保持一致。
        // 先普通读取时段取得serviceId，锁服务后再锁时段并重新校验受理状态。
        ServiceSlot slotSnapshot = slotMapper.selectById(request.slotId());
        if (slotSnapshot == null) {
            throw BusinessException.notFound("预约时段不存在");
        }
        ServiceItem service = itemMapper.findByIdForUpdate(slotSnapshot.getServiceId());
        if (service == null || !Boolean.TRUE.equals(service.getEnabled())) {
            throw BusinessException.badRequest("服务已下架，无法预约");
        }

        ServiceSlot slot = slotMapper.findByIdForUpdate(request.slotId());
        if (slot == null) {
            throw BusinessException.notFound("预约时段不存在");
        }
        if (!Boolean.TRUE.equals(slot.getEnabled())) {
            throw BusinessException.badRequest("该时段已关闭预约");
        }
        if (!slot.getStartAt().isAfter(now)) {
            throw BusinessException.badRequest("该时段已开始，无法预约");
        }

        // 3. 扣名额：条件更新，影响行数 0 即表示名额已满
        int occupied = slotMapper.occupySlot(slot.getId(), now);
        if (occupied != 1) {
            throw BusinessException.conflict("该时段名额已满，请选择其他时段");
        }

        // 4. 建单：价格与服务名取快照，之后改价不影响历史订单
        ServiceOrder order = new ServiceOrder();
        order.setOrderNo(generateOrderNo(now));
        order.setElderId(elderId);
        order.setSlotId(slot.getId());
        order.setServiceId(service.getId());
        order.setStatus(OrderStatus.PENDING_ASSIGNMENT.name());
        order.setServiceNameSnapshot(service.getName());
        order.setPriceSnapshot(service.getPrice());
        order.setSlotStartSnapshot(slot.getStartAt());
        order.setSlotEndSnapshot(slot.getEndAt());
        order.setContactName(request.contactName());
        order.setContactPhone(request.contactPhone());
        order.setAddressSnapshot(request.address());
        order.setVersion(0);
        order.setRequestId(request.requestId());
        order.setRequestHash(requestHash);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        orderMapper.insert(order);

        writeLog(order.getId(), "CREATE", null, order.getStatus(), "老人提交预约");
        return order;
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
        return "SO" + now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + (int) (Math.random() * 9000 + 1000);
    }
}
