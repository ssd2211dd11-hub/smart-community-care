package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 显式执行：真实页面、HTTP、专用测试库和正常调度，未调用独立模拟器。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "server.port=8890","sa-token.token-name=scc-token","care.simulator.enabled=true","care.simulator.schedule-interval-ms=1000"})
@AutoConfigureMockMvc
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class UsageFeedbackBrowserIT extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ServiceSlotMapper slots;
    Path work=Path.of(System.getProperty("care.feedback.browser.work", "../开发资料/work/使用反馈整改"));
    @Test void slotDeviceClaimFulfillmentAndRatingThroughRealPages() throws Exception {
        assertDatabaseIs(TEST_DATABASE);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device",Integer.class)).isZero();
        Files.createDirectories(work);
        try {
            Long elder=createElder("it_feedback_elder");
            createElder("it_feedback_other");
            createAccount("it_feedback_admin",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
            Long staff=createAccount("it_feedback_staff",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
            String elderToken=loginAndGetToken(mvc,"it_feedback_elder",ELDER_PASSWORD);
            String adminToken=loginAndGetToken(mvc,"it_feedback_admin",ADMIN_PASSWORD);
            Long item=createFixtureItem(i->i.setName("使用反馈验收服务"));
            ServiceSlot activeSlot=slot(item,LocalDateTime.now().plusDays(1));
            Long activeOrder=book(activeSlot.getId(),elderToken);
            Long adminConfirmOrder=book(activeSlot.getId(),elderToken);
            // 仅调整本次登记夹具的业务时间，让浏览器可立即演练履约，不改正式订单或生产规则。
            LocalDateTime activeStart=LocalDateTime.now().minusMinutes(20),activeEnd=LocalDateTime.now().plusHours(2);
            jdbcTemplate.update("UPDATE service_slot SET start_at=?,end_at=? WHERE id=? AND service_id=?",activeStart,activeEnd,activeSlot.getId(),item);
            jdbcTemplate.update("UPDATE service_order SET slot_start_snapshot=?,slot_end_snapshot=? WHERE id IN (?,?) AND slot_id=?",activeStart,activeEnd,activeOrder,adminConfirmOrder,activeSlot.getId());
            ServiceSlot futureSlot=slot(item,LocalDateTime.now().plusDays(2));
            Long futureOrder=book(futureSlot.getId(),elderToken);
            mvc.perform(post("/api/service-orders/{id}/assign",futureOrder).header("scc-token","Bearer "+adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"staffId\":\""+staff+"\",\"version\":0}" )).andExpect(status().isOk());
            String staffToken=loginAndGetToken(mvc,"it_feedback_staff",STAFF_PASSWORD);
            mvc.perform(post("/api/service-orders/{id}/accept",futureOrder).header("scc-token","Bearer "+staffToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"reason\":\"登记未来任务：确认接单\"}" )).andExpect(status().isOk());
            mvc.perform(post("/api/service-orders/{id}/claim",adminConfirmOrder).header("scc-token","Bearer "+staffToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}" )).andExpect(status().isOk());
            mvc.perform(post("/api/service-orders/{id}/start",adminConfirmOrder).header("scc-token","Bearer "+staffToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}" )).andExpect(status().isOk());
            mvc.perform(post("/api/service-orders/{id}/submit-result",adminConfirmOrder).header("scc-token","Bearer "+staffToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2,\"result\":\"登记夹具：实际服务已完成，等待管理员核实\"}" )).andExpect(status().isOk());
            Long pagingElder=createElder("it_feedback_paging");
            for(int i=1;i<=25;i++) {
                mvc.perform(post("/api/devices").header("scc-token","Bearer "+adminToken)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"deviceNo\":\"IT-FEEDBACK-PAGING-"+i+"\",\"deviceName\":\"分页设备"+i+"\",\"deviceType\":\"LIGHT\",\"elderId\":\""+pagingElder+"\"}" ))
                    .andExpect(status().isOk());
            }
            String deviceResponse=mvc.perform(post("/api/devices").header("scc-token","Bearer "+adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"deviceNo\":\"IT-FEEDBACK-LIGHT\",\"deviceName\":\"验收电灯\",\"deviceType\":\"LIGHT\",\"elderId\":\""+elder+"\"}" ))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            Long deviceId=json.readTree(deviceResponse).path("data").path("id").asLong();
            json.writeValue(work.resolve("browser-fixtures.json").toFile(),Map.of(
                "database",TEST_DATABASE,"serviceId",item.toString(),"deviceId",deviceId.toString(),
                "orderId",activeOrder.toString(),"orderNo",jdbcTemplate.queryForObject("SELECT order_no FROM service_order WHERE id=?",String.class,activeOrder),
                "adminConfirmOrderId",adminConfirmOrder.toString(),"adminConfirmOrderNo",jdbcTemplate.queryForObject("SELECT order_no FROM service_order WHERE id=?",String.class,adminConfirmOrder),
                "futureOrderNo",jdbcTemplate.queryForObject("SELECT order_no FROM service_order WHERE id=?",String.class,futureOrder),
                "newSlotStart",LocalDateTime.now().plusDays(3).withHour(9).withMinute(0).withSecond(0).withNano(0).toString(),
                "newSlotEnd",LocalDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0).toString()));
            ProcessBuilder builder=new ProcessBuilder("node","src/test/browser/feedback-acceptance.cjs");
            builder.environment().put("FEEDBACK_ADMIN_PASSWORD",ADMIN_PASSWORD);
            builder.environment().put("FEEDBACK_ELDER_PASSWORD",ELDER_PASSWORD);
            builder.environment().put("FEEDBACK_STAFF_PASSWORD",STAFF_PASSWORD);
            builder.environment().put("FEEDBACK_WORK",work.toAbsolutePath().toString());
            builder.redirectErrorStream(true);builder.redirectOutput(work.resolve("browser-run.log").toFile());
            Process browser=builder.start();
            try { assertThat(browser.waitFor(180,TimeUnit.SECONDS)).isTrue();assertThat(browser.exitValue()).isZero(); }
            finally { if(browser.isAlive())browser.destroyForcibly(); }
            var report=json.readTree(work.resolve("browser-results.json").toFile());
            assertThat(report.path("complete").asBoolean()).isTrue();
            Map<String,Object> db=new LinkedHashMap<>();
            db.put("order",jdbcTemplate.queryForMap("SELECT id,status,staff_id,service_result,version FROM service_order WHERE id=?",activeOrder));
            db.put("actions",jdbcTemplate.queryForList("SELECT action,COUNT(*) AS amount FROM service_order_log WHERE order_id=? GROUP BY action ORDER BY action",activeOrder));
            db.put("review",jdbcTemplate.queryForMap("SELECT order_id,elder_id,rating,content FROM service_review WHERE order_id=?",activeOrder));
            db.put("slotCount",jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_slot WHERE service_id=?",Integer.class,item));
            db.put("occupied",jdbcTemplate.queryForObject("SELECT occupied_count FROM service_slot WHERE id=?",Integer.class,activeSlot.getId()));
            db.put("device",jdbcTemplate.queryForMap("SELECT id,simulator_running,power_status FROM device WHERE id=?",deviceId));
            json.writeValue(work.resolve("browser-database.json").toFile(),db);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,activeOrder)).isEqualTo("COMPLETED");
            assertThat(jdbcTemplate.queryForObject("SELECT service_result FROM service_order WHERE id=?",String.class,activeOrder)).isEqualTo("实际完成居室整理，物品归位，老人确认无遗漏。");
            for(String action:List.of("CLAIM","START","SUBMIT","CONFIRM")) assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=? AND action=?",Integer.class,activeOrder,action)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_review WHERE order_id=? AND elder_id=? AND rating=4",Integer.class,activeOrder,elder)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_slot WHERE service_id=?",Integer.class,item)).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject("SELECT occupied_count FROM service_slot WHERE id=?",Integer.class,activeSlot.getId())).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,adminConfirmOrder)).isEqualTo("COMPLETED");
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=? AND action='CONFIRM' AND reason=?",Integer.class,adminConfirmOrder,"管理员代确认：已电话核实服务完成，老人同意社区代确认。")).isEqualTo(1);
            db.put("adminConfirmation",jdbcTemplate.queryForMap("SELECT status,version FROM service_order WHERE id=?",adminConfirmOrder));
            db.put("pagingDeviceCount",jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device WHERE elder_id=?",Integer.class,pagingElder));
            assertThat(db.get("pagingDeviceCount")).isEqualTo(25);
            json.writeValue(work.resolve("browser-database.json").toFile(),db);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=? AND status='SUCCEEDED'",Integer.class,deviceId)).isEqualTo(1);
        } finally { clearAllData(); }
    }
    ServiceSlot slot(Long item,LocalDateTime start) {
        ServiceSlot slot=new ServiceSlot();slot.setServiceId(item);slot.setStartAt(start);slot.setEndAt(start.plusHours(1));
        slot.setCapacity(5);slot.setOccupiedCount(0);slot.setEnabled(true);slot.setCreatedAt(LocalDateTime.now());slot.setUpdatedAt(LocalDateTime.now());slots.insert(slot);return slot;
    }
    Long book(Long slot,String token) throws Exception {
        String response=mvc.perform(post("/api/service-orders").header("scc-token","Bearer "+token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":\""+slot+"\",\"contactName\":\"验收联系人\",\"contactPhone\":\"13900008888\",\"address\":\"登记验收地址\",\"requestId\":\""+UUID.randomUUID()+"\"}" ))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();return json.readTree(response).path("data").path("id").asLong();
    }
}
