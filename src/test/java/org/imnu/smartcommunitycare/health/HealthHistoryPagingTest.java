package org.imnu.smartcommunitycare.health;
import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.health.mapper.HealthRecordMapper;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecord;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDateTime;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@AutoConfigureMockMvc
class HealthHistoryPagingTest extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired HealthRecordMapper records;
    @Test void olderHistoryHasExactFiltersTotalsAndOwnership() throws Exception {
        Long own=createElder("it_v13_history_own"),other=createElder("it_v13_history_other");
        createAccount("it_v13_history_staff",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        for(int i=0;i<6;i++) {
            var record=new HealthRecord();record.setElderId(i==5?other:own);record.setHeartRate(72);
            record.setMeasuredAt(LocalDateTime.parse("2026-01-03T10:00:00").plusMinutes(i));record.setCreatedAt(LocalDateTime.now());
            record.setMode("HISTORY");record.setSource(i==3?"DEMO":"MANUAL");record.setVoided(i==4);records.insert(record);
        }
        String token=loginAndGetToken(mvc,"it_v13_history_own",ELDER_PASSWORD);
        mvc.perform(get("/api/health-records/paged").header("scc-test-token","Bearer "+token).param("fromDate","2026-01-03")
                .param("toDate","2026-01-03").param("source","MANUAL").param("voided","false").param("pageSize","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.records.length()").value(1));
        mvc.perform(get("/api/health-records/paged").header("scc-test-token","Bearer "+token).param("pageNum","2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(5)).andExpect(jsonPath("$.data.records.length()").value(0));
        mvc.perform(get("/api/health-records/paged").header("scc-test-token","Bearer "+token).param("elderId",other.toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/health-records/paged").header("scc-test-token","Bearer "+token).param("fromDate","2026-02-01").param("toDate","2026-01-01"))
                .andExpect(status().isBadRequest());
        String staff=loginAndGetToken(mvc,"it_v13_history_staff",STAFF_PASSWORD);
        mvc.perform(get("/api/health-records/paged").header("scc-test-token","Bearer "+staff)).andExpect(status().isForbidden());
    }
}
