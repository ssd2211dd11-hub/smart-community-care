package org.imnu.smartcommunitycare.order.service;

import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 创建失败后的兜底回查。
 *
 * <p><b>为什么必须单独一个 Bean</b>：Spring 的声明式事务基于代理，
 * 同类内部直接调用带 {@code @Transactional} 的方法不会经过代理，
 * {@code REQUIRES_NEW} 不会生效。把回查放到独立 Bean 里，
 * 它才会真正开启新事务，绕开已经标记回滚的失败事务。
 */
@Service
@RequiredArgsConstructor
public class OrderRequestLookup {

    private final ServiceOrderMapper orderMapper;

    /**
     * 在新事务里回查同一防重键对应的订单。
     *
     * @param elderId     下单人
     * @param requestId   防重键
     * @param requestHash 标准化请求摘要
     * @return 同一 requestId 且内容一致的订单；不存在则 null
     * @throws BusinessException 同一 requestId 下内容不一致
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ServiceOrder findExistingForRetry(Long elderId, String requestId, String requestHash) {
        ServiceOrder existing = orderMapper.findByRequest(elderId, requestId);
        if (existing == null) {
            return null;
        }
        if (!existing.getRequestHash().equals(requestHash)) {
            throw BusinessException.conflict("同一请求标识下内容不一致，请刷新后重试");
        }
        return existing;
    }
}
