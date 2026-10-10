package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 自主领取保持隐私、归属、版本与名额；真正并发验证人员/管理员竞争。 */
@AutoConfigureMockMvc
class OrderClaimTest extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ServiceSlotMapper slots;
    String elderToken, adminToken, staffToken, otherToken;
    Long staffId, otherId, orderId, slotId;
    final String header="scc-test-token";

    @BeforeEach void fixture() throws Exception {
        createElder("it_claim_elder");
        createAccount("it_claim_admin",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
        staffId=createAccount("it_claim_staff",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        otherId=createAccount("it_claim_other",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        elderToken=loginAndGetToken(mvc,"it_claim_elder",ELDER_PASSWORD);
        adminToken=loginAndGetToken(mvc,"it_claim_admin",ADMIN_PASSWORD);
        staffToken=loginAndGetToken(mvc,"it_claim_staff",STAFF_PASSWORD);
        otherToken=loginAndGetToken(mvc,"it_claim_other",STAFF_PASSWORD);
        Long item=createFixtureItem(i->i.setName("自主领取验收服务"));
        ServiceSlot slot=new ServiceSlot();slot.setServiceId(item);
        slot.setStartAt(LocalDateTime.now().plusDays(1));slot.setEndAt(slot.getStartAt().plusHours(1));
        slot.setCapacity(5);slot.setOccupiedCount(0);slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());slot.setUpdatedAt(LocalDateTime.now());slots.insert(slot);slotId=slot.getId();
        String response=mvc.perform(post("/api/service-orders").header(header,"Bearer "+elderToken)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"slotId":"%s","contactName":"私有联系人","contactPhone":"13900008888","address":"私有地址","requestId":"%s"}
                """.formatted(slotId,UUID.randomUUID()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        orderId=json.readTree(response).path("data").path("id").asLong();
    }
    int claim(String token,int version) throws Exception {
        return mvc.perform(post("/api/service-orders/{id}/claim",orderId).header(header,"Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+"}"))
                .andReturn().getResponse().getStatus();
    }
    @Test void poolExposesOnlySummaryAndClaimGrantsOnlyWinnerDetail() throws Exception {
        var response=mvc.perform(get("/api/service-orders/claimable/paged").header(header,"Bearer "+staffToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("自主领取验收服务").doesNotContain("contactName","contactPhone","addressSnapshot","elderId","requestHash","私有联系人","私有地址");
        mvc.perform(get("/api/service-orders/{id}/view",orderId).header(header,"Bearer "+staffToken)).andExpect(status().isNotFound());
        assertThat(claim(staffToken,0)).isEqualTo(200);
        mvc.perform(get("/api/service-orders/my-participations/paged").header(header,"Bearer "+staffToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].orderId").value(orderId.toString()));
        mvc.perform(get("/api/service-orders/{id}/view",orderId).header(header,"Bearer "+staffToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        mvc.perform(get("/api/service-orders/{id}/view",orderId).header(header,"Bearer "+otherToken)).andExpect(status().isNotFound());
        assertThat(claim(otherToken,0)).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("SELECT occupied_count FROM service_slot WHERE id=?",Integer.class,slotId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=? AND action='CLAIM'",Integer.class,orderId)).isEqualTo(1);
    }
    @Test void claimRequiresStaffRoleAndClientVersion() throws Exception {
        for(String token:List.of(elderToken,adminToken)) {
            mvc.perform(get("/api/service-orders/claimable/paged").header(header,"Bearer "+token)).andExpect(status().isForbidden());
            assertThat(claim(token,0)).isEqualTo(403);
        }
        mvc.perform(post("/api/service-orders/{id}/claim",orderId).header(header,"Bearer "+staffToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isBadRequest());
        assertThat(claim(staffToken,99)).isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject("SELECT staff_id FROM service_order WHERE id=?",Long.class,orderId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=? AND action='CLAIM'",Integer.class,orderId)).isZero();
    }
    @Test void twoStaffRaceHasOneWinnerAndOneLogWithoutChangingOccupancy() throws Exception {
        List<Integer> results=race(()->claim(staffToken,0),()->claim(otherToken,0));
        assertThat(results).containsExactlyInAnyOrder(200,409);
        Long winner=jdbcTemplate.queryForObject("SELECT staff_id FROM service_order WHERE id=?",Long.class,orderId);
        assertThat(winner).isEqualTo(results.get(0)==200?staffId:otherId);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,orderId)).isEqualTo("ACCEPTED");
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM service_order WHERE id=?",Integer.class,orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=? AND action='CLAIM'",Integer.class,orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT occupied_count FROM service_slot WHERE id=?",Integer.class,slotId)).isEqualTo(1);
    }
    @Test void administratorAssignmentAndStaffClaimCannotOverwriteEachOther() throws Exception {
        List<Integer> results=race(()->mvc.perform(post("/api/service-orders/{id}/assign",orderId)
                .header(header,"Bearer "+adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"staffId\":\""+otherId+"\",\"version\":0}" )).andReturn().getResponse().getStatus(),()->claim(staffToken,0));
        assertThat(results).containsExactlyInAnyOrder(200,409);
        assertThat(jdbcTemplate.queryForObject("SELECT staff_id FROM service_order WHERE id=?",Long.class,orderId)).isEqualTo(results.get(0)==200?otherId:staffId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=? AND action IN('ASSIGN','CLAIM')",Integer.class,orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT occupied_count FROM service_slot WHERE id=?",Integer.class,slotId)).isEqualTo(1);
    }
    private List<Integer> race(Callable<Integer> first,Callable<Integer> second) throws Exception {
        CyclicBarrier gate=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);return first.call();});
            Future<Integer> b=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);return second.call();});
            return List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
    }
}
