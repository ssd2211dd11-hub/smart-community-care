package org.imnu.smartcommunitycare.alert;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class P3EventActionContractTest extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    private Long eventId, staffId, otherId, elderUserId;
    private String admin, elder, staff, intruder;
    @BeforeEach void prepare() throws Exception {
        createAccount("it_eventfinal_admin",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
        createElder("it_eventfinal_elder",ELDER_PASSWORD);
        elderUserId=userMapper.findByUsername("it_eventfinal_elder").getId();
        createElder("it_eventfinal_intruder",ELDER_PASSWORD);
        staffId=createAccount("it_eventfinal_staff",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        otherId=createAccount("it_eventfinal_other",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        admin=loginAndGetToken(mvc,"it_eventfinal_admin",ADMIN_PASSWORD);
        elder=loginAndGetToken(mvc,"it_eventfinal_elder",ELDER_PASSWORD);
        intruder=loginAndGetToken(mvc,"it_eventfinal_intruder",ELDER_PASSWORD);
        staff=loginAndGetToken(mvc,"it_eventfinal_staff",STAFF_PASSWORD);
        String requestId="event-contract-"+java.util.UUID.randomUUID();
        mvc.perform(post("/api/alert-events/help-calls").header("scc-test-token","Bearer "+elder)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"callType":"OTHER","contactPhone":"13900001111","description":"登记事件夹具","requestId":"%s"}
                """.formatted(requestId))).andExpect(status().isOk());
        eventId=jdbcTemplate.queryForObject("SELECT id FROM alert_event WHERE request_id=?",Long.class,requestId);
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(String action,String token) {
        return post("/api/alert-events/"+eventId+"/"+action).header("scc-test-token","Bearer "+token)
                .param("assigneeId",staffId.toString()).param("newHandlerId",otherId.toString())
                .param("reason","登记原因").param("result","RESOLVED").param("note","登记说明");
    }
    @ParameterizedTest
    @CsvSource({"claim,PENDING,admin","assign,PENDING,admin","accept,ASSIGNED,staff",
                "transfer,PROCESSING,admin","close,PROCESSING,staff","cancel,PENDING,elder"})
    void missingAndStaleVersionHaveNoSideEffects(String action,String state,String role) throws Exception {
        jdbcTemplate.update("UPDATE alert_event SET status=?,handler_id=?,version=131 WHERE id=?",state,action.equals("cancel")?null:staffId,eventId);
        var before=jdbcTemplate.queryForMap("SELECT * FROM alert_event WHERE id=?",eventId);
        int logs=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alert_event_log WHERE event_id=?",Integer.class,eventId);
        String token=role.equals("admin")?admin:role.equals("elder")?elder:staff;
        mvc.perform(action(action,token)).andExpect(status().isBadRequest());
        mvc.perform(action(action,token).param("version","130")).andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM alert_event WHERE id=?",eventId)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alert_event_log WHERE event_id=?",Integer.class,eventId)).isEqualTo(logs);
    }
    @Test void anotherElderCannotCancelTheOwnersHelpCall() throws Exception {
        var before=jdbcTemplate.queryForMap("SELECT * FROM alert_event WHERE id=?",eventId);
        mvc.perform(action("cancel",intruder).param("version","0"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM alert_event WHERE id=?",eventId)).isEqualTo(before);
    }
    @Test void transferCannotAssignAnElderAsHandler() throws Exception {
        jdbcTemplate.update("UPDATE alert_event SET status='PROCESSING',handler_id=? WHERE id=?",staffId,eventId);
        mvc.perform(post("/api/alert-events/"+eventId+"/transfer").header("scc-test-token","Bearer "+admin)
                .param("version","0").param("reason","登记转派原因").param("newHandlerId",elderUserId.toString()))
                .andExpect(status().isBadRequest());
        assertThat(jdbcTemplate.queryForObject("SELECT handler_id FROM alert_event WHERE id=?",Long.class,eventId)).isEqualTo(staffId);
    }
}
