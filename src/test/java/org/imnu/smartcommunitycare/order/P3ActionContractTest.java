package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class P3ActionContractTest extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ServiceSlotMapper slots;
    private String admin, elder, staff, otherStaff;
    private Long staffId, otherId, orderId, slotId;

    @BeforeEach void prepare() throws Exception {
        createAccount("it_p3contract_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        createElder("it_p3contract_elder", ELDER_PASSWORD);
        staffId = createAccount("it_p3contract_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        otherId = createAccount("it_p3contract_other", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        admin = loginAndGetToken(mvc, "it_p3contract_admin", ADMIN_PASSWORD);
        elder = loginAndGetToken(mvc, "it_p3contract_elder", ELDER_PASSWORD);
        staff = loginAndGetToken(mvc, "it_p3contract_staff", STAFF_PASSWORD);
        otherStaff = loginAndGetToken(mvc, "it_p3contract_other", STAFF_PASSWORD);
        Long item = createFixtureItem(it -> { it.setName("P3契约验收服务"); it.setPrice(new BigDecimal("40.00")); });
        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(item); slot.setStartAt(LocalDateTime.now().plusDays(1));
        slot.setEndAt(LocalDateTime.now().plusDays(1).plusHours(1));
        slot.setCapacity(5); slot.setOccupiedCount(0); slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now()); slot.setUpdatedAt(LocalDateTime.now()); slots.insert(slot);
        slotId = slot.getId();
        mvc.perform(post("/api/service-orders").header("scc-test-token", "Bearer " + elder)
                .contentType(MediaType.APPLICATION_JSON).content("""
                {"slotId":"%s","contactName":"契约联系人","contactPhone":"13900001111",
                 "address":"登记夹具地址","requestId":"contract-%s"}
                """.formatted(slotId, java.util.UUID.randomUUID())))
                .andExpect(status().isOk());
        orderId = jdbcTemplate.queryForObject("SELECT id FROM service_order WHERE slot_id = ?", Long.class, slotId);
    }

    private String token(String role) { return switch (role) { case "admin" -> admin; case "elder" -> elder; default -> staff; }; }
    private String body(String action, Integer version) {
        String extra = switch (action) {
            case "assign" -> "\"staffId\":\"" + staffId + "\",";
            case "transfer" -> "\"newStaffId\":\"" + otherId + "\",";
            default -> "";
        };
        return "{" + extra + "\"reason\":\"登记契约原因\",\"result\":\"登记契约结果\""
                + (version == null ? "" : ",\"version\":" + version) + "}";
    }
    private void state(String state, String action) {
        jdbcTemplate.update("UPDATE service_order SET status=?,staff_id=?,version=131,slot_start_snapshot=? WHERE id=?",
                state, staffId, java.sql.Timestamp.valueOf(action.equals("cancel") ? LocalDateTime.now().plusDays(1) : LocalDateTime.now().minusHours(1)), orderId);
    }
    private Map<String,Object> row() { return jdbcTemplate.queryForMap("SELECT * FROM service_order WHERE id=?", orderId); }
    private int logCount() { return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=?", Integer.class, orderId); }

    @ParameterizedTest
    @CsvSource({"assign,PENDING_ASSIGNMENT,admin", "transfer,ACCEPTED,admin", "accept,ASSIGNED,staff",
        "reject,ASSIGNED,staff", "start,ACCEPTED,staff", "submit-result,IN_SERVICE,staff", "confirm,PENDING_CONFIRMATION,elder",
        "confirm-as-admin,PENDING_CONFIRMATION,admin", "return,PENDING_CONFIRMATION,elder", "terminate,IN_SERVICE,admin", "cancel,ASSIGNED,elder"})
    void missingAndStaleVersionNeverMutateOrder(String action, String state, String role) throws Exception {
        state(state, action);
        Map<String,Object> before = row(); int logs = logCount(); int occupied = slots.selectById(slotId).getOccupiedCount();
        for (Integer version : new Integer[]{null, 130}) {
            mvc.perform(post("/api/service-orders/"+orderId+"/"+action).header("scc-test-token", "Bearer "+token(role))
                    .contentType(MediaType.APPLICATION_JSON).content(body(action, version)))
                    .andExpect(status().is(version == null ? 400 : 409));
            assertThat(row()).isEqualTo(before);
            assertThat(logCount()).isEqualTo(logs);
            assertThat(slots.selectById(slotId).getOccupiedCount()).isEqualTo(occupied);
        }
    }

    @ParameterizedTest
    @CsvSource({"accept,ASSIGNED", "reject,ASSIGNED", "start,ACCEPTED", "submit-result,IN_SERVICE"})
    void wrongStaffActionIsIndistinguishableFromMissingOrder(String action, String state) throws Exception {
        state(state, action); Map<String,Object> before = row(); int logs = logCount();
        for (long id : new long[]{orderId, Long.MAX_VALUE}) {
            mvc.perform(post("/api/service-orders/"+id+"/"+action).header("scc-test-token", "Bearer "+otherStaff)
                    .contentType(MediaType.APPLICATION_JSON).content(body(action,131)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("订单不存在"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        assertThat(row()).isEqualTo(before); assertThat(logCount()).isEqualTo(logs);
    }

    @Test void assignedOrderCanBeTransferredBeforeAcceptance() throws Exception {
        state("ASSIGNED", "transfer");
        mvc.perform(post("/api/service-orders/"+orderId+"/transfer").header("scc-test-token", "Bearer "+admin)
                .contentType(MediaType.APPLICATION_JSON).content(body("transfer",131)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.staffId").value(otherId.toString()));
        assertThat(row().get("status")).isEqualTo("ASSIGNED");
        assertThat(row().get("accepted_at")).isNull();
        assertThat(logCount()).isEqualTo(2);
    }

    @Test void ratingMustBePresentAndValid() throws Exception {
        state("COMPLETED", "review");
        for (String body : new String[]{"{}", "{\"rating\":null}", "{\"rating\":0}", "{\"rating\":6}"}) {
            mvc.perform(post("/api/service-orders/"+orderId+"/review").header("scc-test-token", "Bearer "+elder)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_review WHERE order_id=?", Integer.class, orderId)).isZero();
    }

    @Test void absentOrMalformedActionBodyIsAClientError() throws Exception {
        state("ACCEPTED", "start");
        Map<String,Object> before = row();
        mvc.perform(post("/api/service-orders/"+orderId+"/start").header("scc-test-token", "Bearer "+staff))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/service-orders/"+orderId+"/start").header("scc-test-token", "Bearer "+staff)
                .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest());
        assertThat(row()).isEqualTo(before);
    }

    @Test void simultaneousReviewsCreateExactlyOneAndLeaveFulfillmentUnchanged() throws Exception {
        state("COMPLETED", "review");
        Map<String,Object> before = row(); int logs = logCount();
        int occupied = slots.selectById(slotId).getOccupiedCount();
        var ready = new java.util.concurrent.CountDownLatch(4);
        var go = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        try {
            for (int i=0;i<4;i++) futures.add(pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("评论并发闸超时");
                return mvc.perform(post("/api/service-orders/"+orderId+"/review")
                        .header("scc-test-token", "Bearer "+elder).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"content\":\"并发评价夹具\"}"))
                        .andReturn().getResponse().getStatus();
            }));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); go.countDown();
            var codes = new java.util.ArrayList<Integer>();
            for (var f:futures) codes.add(f.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200,409,409,409);
        } finally {
            go.countDown(); pool.shutdown();
            assertThat(pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_review WHERE order_id=?", Integer.class, orderId)).isEqualTo(1);
        assertThat(row()).isEqualTo(before); assertThat(logCount()).isEqualTo(logs);
        assertThat(slots.selectById(slotId).getOccupiedCount()).isEqualTo(occupied);
    }
}
