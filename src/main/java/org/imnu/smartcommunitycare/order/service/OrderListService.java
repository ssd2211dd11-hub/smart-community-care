package org.imnu.smartcommunitycare.order.service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.imnu.smartcommunitycare.common.api.PageQuery.PageResult;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.elder.service.ElderProfileService;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.request.OrderPageQuery;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.Set;
@Service @RequiredArgsConstructor
public class OrderListService {
    private final ServiceOrderMapper orders;
    private final ElderProfileService elders;
    @Transactional(readOnly=true)
    public PageResult<ServiceOrder> page(OrderPageQuery query,UserRole allowed) {
        LoginContext.requireAnyRole(allowed);
        var filter=Wrappers.<ServiceOrder>lambdaQuery();
        // 本人订单的旧契约不使用elderId参数；始终取会话，兼容既有调用。
        if(allowed==UserRole.ELDER)filter.eq(ServiceOrder::getElderId,elders.ownElderId());
        else if(allowed==UserRole.SERVICE_STAFF) {
            Long own=LoginContext.requireUserId();
            if(query.getElderId()!=null||query.getStaffId()!=null&&!own.equals(query.getStaffId()))throw BusinessException.notFound("任务不存在");
            filter.eq(ServiceOrder::getStaffId,own);
        } else {
            if(query.getElderId()!=null)filter.eq(ServiceOrder::getElderId,elders.resolveTargetElderId(query.getElderId()));
            if(query.getStaffId()!=null)filter.eq(ServiceOrder::getStaffId,query.getStaffId());
        }
        if(query.getStatus()!=null&&!query.getStatus().isBlank()) {
            if(!Set.of("PENDING_ASSIGNMENT","ASSIGNED","ACCEPTED","IN_SERVICE","PENDING_CONFIRMATION","COMPLETED","CANCELLED","TERMINATED").contains(query.getStatus()))throw BusinessException.badRequest("订单状态不合法");
            filter.eq(ServiceOrder::getStatus,query.getStatus());
        }
        if("CURRENT".equals(query.getScope()))filter.notIn(ServiceOrder::getStatus,"COMPLETED","CANCELLED","TERMINATED");
        else if("HISTORY".equals(query.getScope()))filter.in(ServiceOrder::getStatus,"COMPLETED","CANCELLED","TERMINATED");
        else if(!"ALL".equals(query.getScope()))throw BusinessException.badRequest("订单范围不合法");
        if(Boolean.TRUE.equals(query.getExcludeEnded()))filter.notIn(ServiceOrder::getStatus,"CANCELLED","TERMINATED");
        if(query.getFromDate()!=null&&query.getToDate()!=null&&query.getFromDate().isAfter(query.getToDate()))throw BusinessException.badRequest("开始日期不能晚于结束日期");
        if(LocalDate.MAX.equals(query.getToDate()))throw BusinessException.badRequest("结束日期超出范围");
        if (!"APPOINTMENT".equals(query.getDateField()) && !"COMPLETION".equals(query.getDateField())) {
            throw BusinessException.badRequest("日期口径不合法");
        }
        if ("COMPLETION".equals(query.getDateField())) {
            if (query.getFromDate() != null) {
                filter.ge(ServiceOrder::getConfirmedAt, query.getFromDate().atStartOfDay());
            }
            if (query.getToDate() != null) {
                filter.lt(ServiceOrder::getConfirmedAt, query.getToDate().plusDays(1).atStartOfDay());
            }
        } else {
            if (query.getFromDate() != null) {
                filter.ge(ServiceOrder::getSlotStartSnapshot, query.getFromDate().atStartOfDay());
            }
            if (query.getToDate() != null) {
                filter.lt(ServiceOrder::getSlotStartSnapshot, query.getToDate().plusDays(1).atStartOfDay());
            }
        }
        long total=orders.selectCount(filter);
        var rows=orders.selectList(filter.orderByDesc(ServiceOrder::getCreatedAt).orderByDesc(ServiceOrder::getId)
                .last("LIMIT "+query.safePageSize()+" OFFSET "+query.offset()));
        return PageResult.of(rows,total,query.safePageNum(),query.safePageSize());
    }
}
