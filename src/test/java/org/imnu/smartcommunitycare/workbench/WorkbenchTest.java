package org.imnu.smartcommunitycare.workbench;
import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.math.BigDecimal;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc @Import(WorkbenchTest.Time.class)
class WorkbenchTest extends BaseIntegrationTest {
    @TestConfiguration static class Time {
        @Bean @Primary Clock workbenchClock(){return Clock.fixed(Instant.parse("2026-10-08T16:30:00Z"),ZoneOffset.UTC);}
    }
    @Autowired MockMvc mvc;
    @Autowired ServiceSlotMapper slots;
    @Autowired ServiceOrderMapper orders;
    @Test void countsEntireScopeAndUsesShanghaiDateAtUtcMidnightBoundary() throws Exception {
        Long elder=createElder("it_v13_workbench_elder");
        Long staff=createAccount("it_v13_workbench_staff",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        Long other=createAccount("it_v13_workbench_other",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        createAccount("it_v13_workbench_admin",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
        Long service=createFixtureItem();
        var slot=new ServiceSlot();slot.setServiceId(service);slot.setStartAt(LocalDateTime.parse("2026-10-09T09:00:00"));
        slot.setEndAt(slot.getStartAt().plusHours(2));slot.setCapacity(100);slot.setOccupiedCount(63);slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());slot.setUpdatedAt(LocalDateTime.now());slots.insert(slot);
        for(int i=0;i<63;i++) {
            var order=new ServiceOrder();order.setOrderNo("IT-V13-WB-"+i);order.setElderId(elder);order.setSlotId(slot.getId());
            order.setServiceId(service);order.setStaffId(i<61?staff:other);order.setStatus("ASSIGNED");
            order.setServiceNameSnapshot("登记工作台服务");order.setPriceSnapshot(BigDecimal.ZERO);
            order.setSlotStartSnapshot(slot.getStartAt());order.setSlotEndSnapshot(slot.getEndAt());order.setContactName("测试联系");
            order.setContactPhone("13900001111");order.setVersion(0);order.setCreatedAt(LocalDateTime.now());order.setUpdatedAt(LocalDateTime.now());orders.insert(order);
        }
        String token=loginAndGetToken(mvc,"it_v13_workbench_staff",STAFF_PASSWORD);
        mvc.perform(get("/api/service-orders/my-tasks/paged").header("scc-test-token","Bearer "+token)
                        .param("fromDate","2026-10-09").param("toDate","2026-10-09").param("pageSize","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(61)).andExpect(jsonPath("$.data.records.length()").value(10));
        mvc.perform(get("/api/service-orders/my-tasks/paged").header("scc-test-token","Bearer "+token)
                        .param("fromDate","2026-10-10").param("toDate","2026-10-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/workbench").header("scc-test-token","Bearer "+token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessDate").value("2026-10-09"))
                .andExpect(jsonPath("$.data.businessZone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.counts.pendingOrders").value(61))
                .andExpect(jsonPath("$.data.counts.todayOrders").value(61))
                .andExpect(jsonPath("$.data.pendingOrders.length()").value(5))
                .andExpect(jsonPath("$.data.todayOrders.length()").value(8));
        String admin=loginAndGetToken(mvc,"it_v13_workbench_admin",ADMIN_PASSWORD);
        mvc.perform(get("/api/workbench").header("scc-test-token","Bearer "+admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.todayOrders").value(63))
                .andExpect(jsonPath("$.data.counts.pendingOrders").value(0))
                .andExpect(jsonPath("$.data.trend.length()").value(7));
        var selected=jdbcTemplate.queryForList("SELECT id FROM service_order WHERE staff_id=? ORDER BY id LIMIT 3",Long.class,staff);
        jdbcTemplate.update("UPDATE service_order SET status='COMPLETED',confirmed_at='2026-10-09 10:00:00',slot_start_snapshot='2026-10-06 09:00:00' WHERE id=?",selected.get(0));
        jdbcTemplate.update("UPDATE service_order SET status='COMPLETED',confirmed_at='2026-10-03 10:00:00',slot_start_snapshot='2026-10-10 09:00:00' WHERE id=?",selected.get(1));
        jdbcTemplate.update("UPDATE service_order SET status='COMPLETED',confirmed_at='2026-10-02 10:00:00' WHERE id=?",selected.get(2));
        mvc.perform(get("/api/workbench").header("scc-test-token","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.counts.todayOrders").value(59))
                .andExpect(jsonPath("$.data.counts.recentCompleted").value(2));
        mvc.perform(get("/api/service-orders/my-tasks/paged").header("scc-test-token","Bearer "+token)
                        .param("scope","ALL").param("fromDate","2026-10-09").param("toDate","2026-10-09").param("excludeEnded","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(59));
        mvc.perform(get("/api/service-orders/my-tasks/paged").header("scc-test-token","Bearer "+token)
                        .param("scope","HISTORY").param("status","COMPLETED").param("dateField","COMPLETION")
                        .param("fromDate","2026-10-03").param("toDate","2026-10-09"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[*].id",org.hamcrest.Matchers.containsInAnyOrder(selected.get(0).toString(),selected.get(1).toString())));
    }
    @Test void eldersCannotReadCommunityOrStaffWorkbench() throws Exception {
        createElder("it_v13_workbench_denied");String token=loginAndGetToken(mvc,"it_v13_workbench_denied",ELDER_PASSWORD);
        mvc.perform(get("/api/workbench").header("scc-test-token","Bearer "+token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/workbench")).andExpect(status().isUnauthorized());
    }
}
