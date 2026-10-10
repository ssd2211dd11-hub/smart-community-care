package org.imnu.smartcommunitycare.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.imnu.smartcommunitycare.alert.mapper.AlertEventMapper;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEvent;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.imnu.smartcommunitycare.workbench.model.WorkbenchResponse;
import org.imnu.smartcommunitycare.workbench.model.WorkbenchResponse.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class WorkbenchService {
    private final ServiceOrderMapper orders;
    private final AlertEventMapper events;
    private final Clock clock;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");

    @Transactional(readOnly=true)
    public WorkbenchResponse summary() {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN,UserRole.SERVICE_STAFF);
        boolean admin=LoginContext.isAdmin();Long staff=admin?null:LoginContext.requireUserId();
        LocalDate date=LocalDate.now(clock.withZone(ZONE));
        LocalDateTime today=date.atStartOfDay(), tomorrow=date.plusDays(1).atStartOfDay();
        var pendingEvents=eventScope(staff);
        if(admin) pendingEvents.in(AlertEvent::getStatus,"PENDING","ASSIGNED").isNull(AlertEvent::getFirstAcceptedAt);
        else pendingEvents.eq(AlertEvent::getStatus,"ASSIGNED");
        var processing=eventScope(staff).eq(AlertEvent::getStatus,"PROCESSING");
        var pendingOrders=orderScope(staff).eq(ServiceOrder::getStatus,admin?"PENDING_ASSIGNMENT":"ASSIGNED");
        var todayOrders=orderScope(staff).ge(ServiceOrder::getSlotStartSnapshot,today).lt(ServiceOrder::getSlotStartSnapshot,tomorrow)
                .notIn(ServiceOrder::getStatus,"CANCELLED","TERMINATED");
        var completed=orderScope(staff).eq(ServiceOrder::getStatus,"COMPLETED")
                .ge(ServiceOrder::getConfirmedAt,date.minusDays(6).atStartOfDay()).lt(ServiceOrder::getConfirmedAt,tomorrow);
        Counts counts=new Counts(events.selectCount(pendingEvents),orders.selectCount(pendingOrders),orders.selectCount(todayOrders),
                events.selectCount(processing),orders.selectCount(completed));
        List<Trend> trend=new ArrayList<>();
        if(admin) for(int day=6;day>=0;day--) {
            LocalDate current=date.minusDays(day);LocalDateTime start=current.atStartOfDay(),end=current.plusDays(1).atStartOfDay();
            trend.add(new Trend(current,events.selectCount(Wrappers.<AlertEvent>lambdaQuery().ge(AlertEvent::getCreatedAt,start).lt(AlertEvent::getCreatedAt,end)),
                    orders.selectCount(Wrappers.<ServiceOrder>lambdaQuery().eq(ServiceOrder::getStatus,"COMPLETED")
                            .ge(ServiceOrder::getConfirmedAt,start).lt(ServiceOrder::getConfirmedAt,end))));
        }
        return new WorkbenchResponse(date,ZONE.getId(),LocalDateTime.now(clock.withZone(ZONE)),counts,
                eventPreview(pendingEvents),eventPreview(processing),orderPreview(pendingOrders,5,false),
                orderPreview(todayOrders,8,true),orderPreview(completed,5,false),trend);
    }
    private LambdaQueryWrapper<ServiceOrder> orderScope(Long staff) {
        return Wrappers.<ServiceOrder>lambdaQuery().eq(staff!=null,ServiceOrder::getStaffId,staff);
    }
    private LambdaQueryWrapper<AlertEvent> eventScope(Long staff) {
        return Wrappers.<AlertEvent>lambdaQuery().eq(staff!=null,AlertEvent::getHandlerId,staff);
    }
    private List<EventPreview> eventPreview(LambdaQueryWrapper<AlertEvent> filter) {
        return events.selectList(filter.orderByAsc(AlertEvent::getResponseDueAt).orderByAsc(AlertEvent::getId).last("LIMIT 5"))
                .stream().map(e->new EventPreview(e.getId().toString(),e.getEventNo(),"HELP_CALL".equals(e.getSourceType())?"居民求助":"健康待核实",e.getStatus())).toList();
    }
    private List<OrderPreview> orderPreview(LambdaQueryWrapper<ServiceOrder> filter,int limit,boolean schedule) {
        if(schedule) filter.orderByAsc(ServiceOrder::getSlotStartSnapshot).orderByAsc(ServiceOrder::getId);
        else filter.orderByDesc(ServiceOrder::getCreatedAt).orderByDesc(ServiceOrder::getId);
        return orders.selectList(filter.last("LIMIT "+limit)).stream()
                .map(o->new OrderPreview(o.getId().toString(),o.getOrderNo(),o.getServiceNameSnapshot(),o.getContactName(),o.getStatus(),o.getSlotStartSnapshot())).toList();
    }
}
