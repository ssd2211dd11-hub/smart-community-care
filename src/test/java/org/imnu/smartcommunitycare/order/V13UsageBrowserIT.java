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

/** 显式入口：真实浏览器、专用测试库、正常定时调度；不纳入日常单元默认扫描。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=8890", "sa-token.token-name=scc-token", "care.simulator.enabled=true",
        "care.simulator.schedule-interval-ms=1000" })
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class V13UsageBrowserIT extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ServiceSlotMapper slots;
    @Autowired ObjectMapper json;
    private final Path work = Path.of(System.getProperty("care.v13.browser.work", "../开发资料/work/CP-P3-V1.3-control"));

    @Test void firstBatchWorksThroughRealPagesAndDatabase() throws Exception {
        assertDatabaseIs(TEST_DATABASE);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device", Integer.class))
                .as("不能让未登记设备参与自动调度").isZero();
        var baseline = nonFixtureFingerprint();
        Files.createDirectories(work);
        try {
            Long elder = createElder("it_v13_usage_elder");
            Long admin = createAccount("it_v13_usage_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
            Long staff = createAccount("it_v13_usage_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
            String elderToken = loginAndGetToken(mvc, "it_v13_usage_elder", ELDER_PASSWORD);
            String adminToken = loginAndGetToken(mvc, "it_v13_usage_admin", ADMIN_PASSWORD);
            String staffToken = loginAndGetToken(mvc, "it_v13_usage_staff", STAFF_PASSWORD);
            Long service = createFixtureItem(item -> item.setName("V1.3分页验收服务"));
            ServiceSlot slot = new ServiceSlot();
            slot.setServiceId(service);
            slot.setStartAt(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
            slot.setEndAt(slot.getStartAt().plusHours(3));
            slot.setCapacity(100); slot.setOccupiedCount(0); slot.setEnabled(true);
            slot.setCreatedAt(LocalDateTime.now()); slot.setUpdatedAt(LocalDateTime.now());
            slots.insert(slot);
            Long lastOrder = null;
            for (int i = 0; i < 31; i++) {
                var response = mvc.perform(post("/api/service-orders")
                                .header("scc-token", "Bearer " + elderToken)
                                .contentType(MediaType.APPLICATION_JSON).content("""
                                  {"slotId":"%s","contactName":"V1.3夹具联系人","contactPhone":"13900001234",
                                   "address":"登记验收地址","requestId":"%s"}
                                  """.formatted(slot.getId(), UUID.randomUUID())))
                        .andExpect(status().isOk()).andReturn();
                lastOrder = Long.valueOf(json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asString());
                mvc.perform(post("/api/service-orders/{id}/assign", lastOrder)
                                .header("scc-token", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON).content("""
                                  {"staffId":"%s","reason":"登记分页夹具","version":0}
                                  """.formatted(staff)))
                        .andExpect(status().isOk());
            }
            mvc.perform(post("/api/service-orders/{id}/accept", lastOrder)
                            .header("scc-token", "Bearer " + staffToken)
                            .contentType(MediaType.APPLICATION_JSON).content(withOrderVersion(lastOrder, "{\"reason\":\"登记已接单分组\"}")))
                    .andExpect(status().isOk());
            Map<String, Object> users = Map.of(
                    "admin", Map.of("username", "it_v13_usage_admin", "id", admin.toString()),
                    "elder", Map.of("username", "it_v13_usage_elder", "id", userMapper.findByUsername("it_v13_usage_elder").getId().toString()),
                    "staff", Map.of("username", "it_v13_usage_staff", "id", staff.toString()));
            json.writeValue(work.resolve("browser-fixtures.json").toFile(), Map.of(
                    "database", TEST_DATABASE, "users", users, "elderId", elder.toString(),
                    "adminTotal", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order", Integer.class),
                    "ownTotal", 31, "serviceId", service.toString(), "slotId", slot.getId().toString()));
            ProcessBuilder builder = new ProcessBuilder("node", "src/test/browser/v13-acceptance.cjs");
            builder.environment().put("V13_ADMIN_PASSWORD", ADMIN_PASSWORD);
            builder.environment().put("V13_ELDER_PASSWORD", ELDER_PASSWORD);
            builder.environment().put("V13_STAFF_PASSWORD", STAFF_PASSWORD);
            builder.environment().put("V13_WORK", work.toAbsolutePath().toString());
            builder.redirectErrorStream(true); builder.redirectOutput(work.resolve("browser-run.log").toFile());
            Process browser = builder.start();
            try {
                assertThat(browser.waitFor(4, TimeUnit.MINUTES)).as("浏览器超时").isTrue();
                assertThat(browser.exitValue()).isZero();
            } finally { if (browser.isAlive()) { browser.destroyForcibly(); browser.waitFor(); } }
            var report = json.readTree(Files.readString(work.resolve("browser-results.json")));
            assertThat(report.path("complete").asBoolean()).isTrue();
            String requestId = report.path("helpRetry").path("requestId").asString();
            assertThat(requestId).isNotBlank();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM alert_event WHERE elder_id=? AND request_id=?", Integer.class, elder, requestId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order WHERE slot_id=?", Integer.class, slot.getId())).isEqualTo(31);
            assertThat(jdbcTemplate.queryForObject("SELECT occupied_count FROM service_slot WHERE id=?", Integer.class, slot.getId())).isEqualTo(31);
            String deviceId = report.path("deviceId").asString();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=? AND status='SUCCEEDED'", Integer.class, deviceId)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM elder_profile ep JOIN sys_user u ON u.id=ep.user_id WHERE u.username='it_v13_usage_created_elder' AND ep.address='登记档案地址'",Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='it_v13_usage_created_staff' AND role='SERVICE_STAFF' AND enabled=1 AND real_name='维护后的人员'",Integer.class)).isEqualTo(1);
            assertThat(nonFixtureFingerprint()).isEqualTo(baseline);
            json.writeValue(work.resolve("browser-database.json").toFile(), Map.of(
                    "database", TEST_DATABASE, "registeredOrders", 31, "occupiedCount", 31,
                    "originalHelpRequestCount", 1, "successfulDeviceCommands", 1,
                    "createdElderAccountAndArchive", 1, "maintainedStaffAccount", 1, "nonFixturePreserved", true));
        } finally {
            clearAllData();
            assertThat(nonFixtureFingerprint()).isEqualTo(baseline);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username LIKE ?", Integer.class, "it\\_v13\\_usage\\_%")).isZero();
            json.writeValue(work.resolve("browser-cleanup.json").toFile(), Map.of(
                    "registeredFixturesRemoved", true, "nonFixturePreserved", true, "database", TEST_DATABASE));
        }
    }

    private Map<String, Object> nonFixtureFingerprint() {
        return Map.of("users", jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username NOT LIKE ?", Integer.class, "it\\_%"),
                "services", jdbcTemplate.queryForObject("SELECT COALESCE(GROUP_CONCAT(CONCAT(id,':',SHA2(CONCAT_WS('|',name,category,description,price,provider,phone,enabled,created_at,updated_at),256)) ORDER BY id SEPARATOR ','),'') FROM service_item WHERE description IS NULL OR description NOT LIKE '%IT-FIXTURE%'", String.class));
    }
}
