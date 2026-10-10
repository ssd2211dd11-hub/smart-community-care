package org.imnu.smartcommunitycare.workbench.model;
import java.time.*;
import java.util.List;
public record WorkbenchResponse(LocalDate businessDate,String businessZone,LocalDateTime dataAsOf,Counts counts,
        List<EventPreview> pendingEvents,List<EventPreview> processingEvents,List<OrderPreview> pendingOrders,
        List<OrderPreview> todayOrders,List<OrderPreview> recentCompleted,List<Trend> trend) {
    public record Counts(long pendingEvents,long pendingOrders,long todayOrders,long processingEvents,long recentCompleted) {}
    public record EventPreview(String id,String eventNo,String title,String status) {}
    public record OrderPreview(String id,String orderNo,String title,String contactName,String status,LocalDateTime startsAt) {}
    public record Trend(LocalDate date,long newEvents,long completedOrders) {}
}
