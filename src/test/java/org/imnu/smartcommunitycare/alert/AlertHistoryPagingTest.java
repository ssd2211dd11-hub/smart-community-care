package org.imnu.smartcommunitycare.alert;
import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.alert.mapper.AlertEventMapper;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEvent;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDateTime;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@AutoConfigureMockMvc
class AlertHistoryPagingTest extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AlertEventMapper events;
    @Autowired org.imnu.smartcommunitycare.alert.mapper.AlertEventLogMapper logs;
    @Test void transferredParticipationDoesNotLeakCurrentContactsOrDetail() throws Exception {
        Long elder=createElder("it_v13_participated_elder");
        Long staff=createAccount("it_v13_participated_staff",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        var event=new AlertEvent();event.setEventNo("IT-V13-PARTICIPATED");event.setElderId(elder);event.setSourceType("HELP_CALL");
        event.setStatus("PENDING");event.setPriority("NORMAL");event.setOccurrenceCount(1);event.setVersion(1);
        event.setContactPhone("13900006666");event.setDescription("不应出现在转出历史的详情");event.setCreatedAt(LocalDateTime.now());event.setUpdatedAt(LocalDateTime.now());events.insert(event);
        var log=new org.imnu.smartcommunitycare.alert.model.entity.AlertEventLog();log.setEventId(event.getId());log.setOperatorId(staff);
        log.setAction("TRANSFER");log.setFromStatus("ASSIGNED");log.setToStatus("PENDING");log.setOperatorSource("USER");log.setCreatedAt(LocalDateTime.now());logs.insert(log);
        String token=loginAndGetToken(mvc,"it_v13_participated_staff",STAFF_PASSWORD);
        String body=mvc.perform(get("/api/alert-events/my-participations/paged").header("scc-test-token","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1)).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("IT-V13-PARTICIPATED","TRANSFER").doesNotContain("13900006666","contactPhone","description","不应出现在转出历史的详情");
        mvc.perform(get("/api/alert-events/{id}",event.getId()).header("scc-test-token","Bearer "+token)).andExpect(status().isNotFound());
    }
    @Test void closedHistoryIsVisibleOnlyInsideRoleAndObjectScope() throws Exception {
        Long own=createElder("it_v13_event_own"),other=createElder("it_v13_event_other");
        Long staff=createAccount("it_v13_event_staff",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        createAccount("it_v13_event_admin",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
        for(int i=0;i<4;i++) {
            var event=new AlertEvent();event.setEventNo("IT-V13-HISTORY-"+i);event.setElderId(i==3?other:own);
            event.setSourceType("HELP_CALL");event.setStatus(i==0?"PENDING":"CLOSED");event.setPriority("NORMAL");
            event.setHandlerId(i==1?staff:null);event.setOccurrenceCount(1);event.setVersion(0);
            event.setCreatedAt(LocalDateTime.now());event.setUpdatedAt(LocalDateTime.now());events.insert(event);
        }
        String elder=loginAndGetToken(mvc,"it_v13_event_own",ELDER_PASSWORD);
        mvc.perform(get("/api/alert-events/paged").header("scc-test-token","Bearer "+elder).param("scope","HISTORY").param("pageSize","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records.length()").value(1));
        mvc.perform(get("/api/alert-events/paged").header("scc-test-token","Bearer "+elder).param("elderId",other.toString())).andExpect(status().isNotFound());
        String personnel=loginAndGetToken(mvc,"it_v13_event_staff",STAFF_PASSWORD);
        mvc.perform(get("/api/alert-events/paged").header("scc-test-token","Bearer "+personnel).param("scope","HISTORY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        String admin=loginAndGetToken(mvc,"it_v13_event_admin",ADMIN_PASSWORD);
        mvc.perform(get("/api/alert-events/paged").header("scc-test-token","Bearer "+admin).param("scope","ALL").param("elderId",own.toString()).param("pageNum","2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.records.length()").value(0));
    }
}
