package org.imnu.smartcommunitycare.device;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式验收：真实 Vue、正常时钟和默认间隔的 @Scheduled；不手动调用 tick。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "server.port=8890","sa-token.token-name=scc-token","care.simulator.enabled=true",
    "care.simulator.schedule-interval-ms=1000"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class P4BrowserAcceptanceIT extends BaseIntegrationTest {
    @Autowired ObjectMapper json;
    final Path work=Path.of(System.getProperty("care.p4.browser.work", "../开发资料/work/CP-P4-P5-control"));
    @Test void actualPagesObserveAutomaticSimulator() throws Exception {
        assertDatabaseIs(TEST_DATABASE);
        // 外部只读盘点后，此入口再次禁止任何未登记设备参与本次自动调度。
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device",Integer.class)).isZero();
        Map<String,Object> baseline=nonFixtureFingerprint();
        Files.createDirectories(work);
        createAccount("it_p4browser_admin",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
        Long owner=createElder("it_p4browser_elder");Long other=createElder("it_p4browser_other");
        createAccount("it_p4browser_staff",STAFF_PASSWORD,UserRole.SERVICE_STAFF,true);
        Map<String,Object> users=new LinkedHashMap<>();
        for(String role:List.of("admin","elder","other","staff"))users.put(role,Map.of("username","it_p4browser_"+role));
        json.writeValue(work.resolve("browser-fixtures.json").toFile(),Map.of("database",TEST_DATABASE,"users",users,"owner",owner.toString(),"otherOwner",other.toString()));
        try {
            ProcessBuilder builder=new ProcessBuilder("node","src/test/browser/p4-acceptance.cjs");
            builder.environment().put("P4_ADMIN_PASSWORD",ADMIN_PASSWORD);
            builder.environment().put("P4_ELDER_PASSWORD",ELDER_PASSWORD);
            builder.environment().put("P4_STAFF_PASSWORD",STAFF_PASSWORD);
            builder.environment().put("P4_WORK",work.toAbsolutePath().toString());
            builder.redirectErrorStream(true);builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            Process browser=builder.start();
            try { assertThat(browser.waitFor(5,java.util.concurrent.TimeUnit.MINUTES)).as("真实页面阶段超时").isTrue();
                assertThat(browser.exitValue()).isZero();
            } finally { if(browser.isAlive()) {browser.destroyForcibly();browser.waitFor();} }
            var report=json.readTree(Files.readString(work.resolve("browser-results.json")));
            long device=Long.parseLong(report.path("fixtures").path("deviceId").asString());
            long noAck=Long.parseLong(report.path("fixtures").path("noAckId").asString());
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM device_command WHERE id=?",String.class,noAck)).isEqualTo("TIMED_OUT");
            assertThat(jdbcTemplate.queryForObject("SELECT simulated_executed_at IS NOT NULL AND acknowledged_at IS NULL FROM device_command WHERE id=?",Integer.class,noAck)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Integer.class,device)).isEqualTo(5);
            assertThat(jdbcTemplate.queryForObject("SELECT enabled FROM device WHERE id=?",Boolean.class,device)).isFalse();
            json.writeValue(work.resolve("browser-database.json").toFile(),Map.of("database",TEST_DATABASE,
                "devices",jdbcTemplate.queryForList("SELECT id,device_no,elder_id,enabled,simulator_running,simulation_mode,simulated_power_status,power_status,last_seen_at,version FROM device ORDER BY id"),
                "commands",jdbcTemplate.queryForList("SELECT id,device_id,elder_id_snapshot,status,target_power_status,result_power_status,simulated_executed_at,acknowledged_at,created_at,expires_at FROM device_command ORDER BY id"),"nonFixturePreserved",nonFixtureFingerprint().equals(baseline)));
            assertThat(nonFixtureFingerprint()).isEqualTo(baseline);
        } finally {
            jdbcTemplate.update("UPDATE device SET simulator_running=0 WHERE elder_id IN (?,?)",owner,other);
            clearAllData();
            assertThat(nonFixtureFingerprint()).isEqualTo(baseline);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device",Integer.class)).isZero();
            json.writeValue(work.resolve("browser-cleanup.json").toFile(),Map.of("registeredFixturesRemoved",true,"nonFixturePreserved",true,"database",TEST_DATABASE));
        }
    }
    Map<String,Object> nonFixtureFingerprint() {
        return Map.of("users",jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username NOT LIKE ?",Integer.class,"it\\_%"),
            "services",jdbcTemplate.queryForObject("SELECT COALESCE(GROUP_CONCAT(CONCAT(id,':',SHA2(CONCAT_WS('|',name,category,description,price,provider,phone,enabled,created_at,updated_at),256)) ORDER BY id SEPARATOR ','),'') FROM service_item WHERE description IS NULL OR description NOT LIKE '%IT-FIXTURE%'",String.class));
    }
}
