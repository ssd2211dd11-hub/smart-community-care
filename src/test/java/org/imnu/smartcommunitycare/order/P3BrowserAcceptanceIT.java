package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式运行的真实浏览器验收：当前源码、真实 HTTP/MySQL，时间仅由测试 Clock 推进。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT, properties={
    "server.port=8890","sa-token.token-name=scc-token","care.simulator.enabled=false"})
class P3BrowserAcceptanceIT extends BaseIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired BrowserClock fixtureClock;
    private final Path work=Path.of(System.getProperty("care.p3.browser.work", "../开发资料/work/CP-P3-final-control"));

    @Test void threeRolesCompleteRegisteredBusinessChains() throws Exception {
        assertDatabaseIs(TEST_DATABASE);
        Files.createDirectories(work);
        Map<String,Object> baseline=nonFixtureCounts();
        Map<String,Object> users=new LinkedHashMap<>();
        for(var pair:Map.of("admin",UserRole.COMMUNITY_ADMIN,"staffA",UserRole.SERVICE_STAFF,"staffB",UserRole.SERVICE_STAFF).entrySet()) {
            String username="it_p3final_"+pair.getKey();
            Long id=createAccount(username,pair.getKey().equals("admin")?ADMIN_PASSWORD:STAFF_PASSWORD,pair.getValue(),true);
            users.put(pair.getKey(),Map.of("username",username,"id",id.toString()));
        }
        createElder("it_p3final_elder",ELDER_PASSWORD);
        users.put("elder",Map.of("username","it_p3final_elder","id",userMapper.findByUsername("it_p3final_elder").getId().toString()));
        LocalDateTime start=LocalDateTime.now(fixtureClock).plusMinutes(20).withSecond(0).withNano(0);
        json.writeValue(work.resolve("browser-fixtures.json").toFile(),Map.of("database",TEST_DATABASE,"users",users,
                "startAt",start.toString(),"endAt",start.plusHours(1).toString(),"nonFixtureBefore",baseline));
        try {
            browser("prepare");
            var phase1=json.readTree(Files.readString(work.resolve("browser-results.json")));
            Long slot=Long.valueOf(phase1.path("fixtures").path("slotId").asString());
            Long normal=Long.valueOf(phase1.path("fixtures").path("normal").asString());
            Long exceptional=Long.valueOf(phase1.path("fixtures").path("exceptional").asString());
            Long cancelled=Long.valueOf(phase1.path("fixtures").path("cancelled").asString());
            assertThat(jdbcTemplate.queryForObject("SELECT occupied_count FROM service_slot WHERE id=?",Integer.class,slot)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,cancelled)).isEqualTo("CANCELLED");
            fixtureClock.advance(Duration.ofMinutes(21));
            browser("finish");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,normal)).isEqualTo("COMPLETED");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_order WHERE id=?",String.class,exceptional)).isEqualTo("TERMINATED");
            assertThat(jdbcTemplate.queryForObject("SELECT occupied_count FROM service_slot WHERE id=?",Integer.class,slot)).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_review WHERE order_id=?",Integer.class,normal)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT rating FROM service_review WHERE order_id=?",Integer.class,normal)).isEqualTo(5);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=? AND action='START'",Integer.class,normal)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_order_log WHERE order_id=? AND action='SUBMIT'",Integer.class,normal)).isEqualTo(2);
            Map<String,Object> db=new LinkedHashMap<>();
            db.put("database",jdbcTemplate.queryForObject("SELECT DATABASE()",String.class));
            db.put("orders",jdbcTemplate.queryForList("SELECT id,order_no,status,staff_id,version,started_at,finished_at,confirmed_at,terminated_at,cancelled_at,service_name_snapshot,price_snapshot FROM service_order WHERE slot_id=? ORDER BY id",slot));
            db.put("logs",jdbcTemplate.queryForList("SELECT order_id,action,from_status,to_status,operator_id,reason FROM service_order_log WHERE order_id IN (SELECT id FROM service_order WHERE slot_id=?) ORDER BY id",slot));
            db.put("slot",jdbcTemplate.queryForMap("SELECT id,capacity,occupied_count FROM service_slot WHERE id=?",slot));
            db.put("nonFixtureAfter",nonFixtureCounts());
            assertThat(nonFixtureCounts()).isEqualTo(baseline);
            json.writeValue(work.resolve("browser-database.json").toFile(),db);
        } finally {
            fixtureClock.advance(Duration.ZERO);
            clearAllData();
            assertThat(nonFixtureCounts()).isEqualTo(baseline);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username LIKE 'it_p3final_%'",Integer.class)).isZero();
            json.writeValue(work.resolve("browser-cleanup.json").toFile(),Map.of("database",TEST_DATABASE,"registeredFixturesRemoved",true,"nonFixturePreserved",true));
        }
    }
    @AfterEach void restoreClock() { fixtureClock.advance(Duration.ZERO); }
    private Map<String,Object> nonFixtureCounts() {
        return Map.of("users",jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username NOT LIKE ?",Integer.class,"it\\_%"),
            "items",jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_item WHERE description IS NULL OR description NOT LIKE '%IT-FIXTURE%'",Integer.class),
            "itemFingerprint",jdbcTemplate.queryForObject("SELECT COALESCE(GROUP_CONCAT(CONCAT(id,':',SHA2(CONCAT_WS('|',name,category,description,price,provider,phone,enabled,created_at,updated_at),256)) ORDER BY id SEPARATOR ','),'') FROM service_item WHERE description IS NULL OR description NOT LIKE '%IT-FIXTURE%'",String.class));
    }
    private void browser(String phase) throws Exception {
        ProcessBuilder builder=new ProcessBuilder("node","src/test/browser/p3-acceptance.cjs",phase);
        builder.environment().put("P3_ADMIN_PASSWORD",ADMIN_PASSWORD);
        builder.environment().put("P3_ELDER_PASSWORD",ELDER_PASSWORD);
        builder.environment().put("P3_STAFF_PASSWORD",STAFF_PASSWORD);
        builder.environment().put("P3_WORK",work.toAbsolutePath().toString());
        builder.redirectErrorStream(true); builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process process=builder.start();
        try {
            if(!process.waitFor(6,java.util.concurrent.TimeUnit.MINUTES))throw new IllegalStateException("浏览器验收超时");
            assertThat(process.exitValue()).as("真实浏览器阶段 "+phase).isZero();
        } finally { if(process.isAlive())process.destroyForcibly(); }
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary BrowserClock browserClock() { return new BrowserClock(); }
    }
    static class BrowserClock extends Clock {
        private volatile Duration offset=Duration.ZERO;
        void advance(Duration duration) { offset=duration; }
        @Override public ZoneId getZone() { return ZoneId.systemDefault(); }
        @Override public Clock withZone(ZoneId zone) { return Clock.offset(Clock.system(zone),offset); }
        @Override public Instant instant() { return Instant.now().plus(offset); }
    }
}
