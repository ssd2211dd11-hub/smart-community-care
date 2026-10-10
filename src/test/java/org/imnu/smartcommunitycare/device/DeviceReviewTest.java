package org.imnu.smartcommunitycare.device;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.device.config.SimulatorProperties;
import org.imnu.smartcommunitycare.device.mapper.DeviceMapper;
import org.imnu.smartcommunitycare.device.mapper.DeviceCommandMapper;
import org.imnu.smartcommunitycare.device.simulation.DeviceSimulatorExecutor;
import org.imnu.smartcommunitycare.device.simulation.DeviceSimulatorScheduler;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实MySQL与正常API的设备回归；spy仅用于明确的故障注入或同步点。
 */
@AutoConfigureMockMvc
class DeviceReviewTest extends BaseIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DeviceMapper devices;
    @MockitoSpyBean DeviceCommandMapper commands;
    @Autowired DeviceSimulatorExecutor executor;
    @Autowired DeviceSimulatorScheduler scheduler;
    @Autowired SimulatorProperties properties;
    @Autowired org.mybatis.spring.SqlSessionTemplate sqlSession;
    String admin, elder, other;
    Long owner, newOwner, device;
    @BeforeEach void fixtures() throws Exception {
        createAccount("it_p4_admin",ADMIN_PASSWORD,UserRole.COMMUNITY_ADMIN,true);
        owner=createElder("it_p4_elder"); newOwner=createElder("it_p4_other");
        admin=loginAndGetToken(mvc,"it_p4_admin",ADMIN_PASSWORD);
        elder=loginAndGetToken(mvc,"it_p4_elder",ELDER_PASSWORD);
        other=loginAndGetToken(mvc,"it_p4_other",ELDER_PASSWORD);
        device=create("IT-P4-REVIEW",owner);
    }
    @AfterEach void cleanup() { clearAllData(); }
    Long create(String no,Long elderId) throws Exception {
        return id(call("POST","/api/devices",admin,"""
            {"deviceNo":"%s","deviceName":"P4登记电灯","deviceType":"LIGHT","elderId":"%s"}
            """.formatted(no,elderId),200));
    }
    MvcResult call(String method,String path,String session,String body,int expected) throws Exception {
        var request=switch(method){case "GET"->get(path);case "PUT"->put(path);default->post(path);};
        request.header("scc-test-token","Bearer "+session);
        if(body!=null)request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andExpect(status().is(expected)).andReturn();
    }
    Long id(MvcResult result) { return Long.valueOf(json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("id").asString()); }
    void start() throws Exception { call("POST","/api/devices/"+device+"/simulator/start",admin,null,200); }
    Long submit(Long d,String key,String session) throws Exception { return id(call("POST","/api/devices/"+d+"/commands",session,"{\"targetPowerStatus\":\"ON\",\"requestId\":\""+key+"\"}",200)); }
    String state(Long command) { return jdbcTemplate.queryForObject("SELECT status FROM device_command WHERE id=?",String.class,command); }
    void ready(Long c) { jdbcTemplate.update("UPDATE device_command SET ready_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),c); }

    @Test void responsesMatchPersistedVersionAndStopDoesNotInventHeartbeat() throws Exception {
        var started=call("POST","/api/devices/"+device+"/simulator/start",admin,null,200);
        assertThat(json.readTree(started.getResponse().getContentAsString()).path("data").path("version").asInt()).isEqualTo(devices.selectById(device).getVersion());
        var mode=call("POST","/api/devices/"+device+"/simulation-mode",admin,"{\"mode\":\"NO_ACK\"}",200);
        assertThat(json.readTree(mode.getResponse().getContentAsString()).path("data").path("version").asInt()).isEqualTo(devices.selectById(device).getVersion());
        LocalDateTime old=LocalDateTime.now().minusMinutes(1).withNano(0);
        jdbcTemplate.update("UPDATE device SET last_seen_at=? WHERE id=?",old,device);
        var stopped=json.readTree(call("POST","/api/devices/"+device+"/simulator/stop",admin,null,200).getResponse().getContentAsString()).path("data");
        assertThat(LocalDateTime.parse(stopped.path("lastSeenAt").asString())).isEqualTo(old);
        assertThat(stopped.path("onlineStatus").asString()).isEqualTo("OFFLINE");
    }
    @Test void ownershipTransferCancelsUnexecutedCommandAndRejectsOldOwner() throws Exception {
        start(); Long c=submit(device,"transfer",elder);
        call("PUT","/api/devices/"+device,admin,"{\"elderId\":\""+newOwner+"\"}",200);
        assertThat(state(c)).isEqualTo("CANCELLED");
        ready(c); executor.tick(device);
        assertThat(devices.selectById(device).getSimulatedPowerStatus()).isEqualTo("OFF");
        call("GET","/api/devices/"+device+"/commands",elder,null,404);
        call("POST","/api/devices/"+device+"/commands",elder,"{\"targetPowerStatus\":\"ON\"}",404);
        submit(device,"new-owner",other);
        assertThat(commands.findById(c).getElderIdSnapshot()).isEqualTo(owner);
    }
    @Test void acceptedRequestCanBeConfirmedAfterStopOrDisable() throws Exception {
        start(); Long c=submit(device,"known-key",elder);
        call("POST","/api/devices/"+device+"/simulator/stop",admin,null,200);
        assertThat(submit(device,"known-key",elder)).isEqualTo(c);
        properties.setEnabled(false);
        try { assertThat(submit(device,"known-key",elder)).isEqualTo(c); }
        finally { properties.setEnabled(true); }
        call("PUT","/api/devices/"+device,admin,"{\"enabled\":false}",200);
        assertThat(submit(device,"known-key",elder)).isEqualTo(c);
        call("POST","/api/devices/"+device+"/commands",elder,"{\"targetPowerStatus\":\"OFF\",\"requestId\":\"known-key\"}",409);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Integer.class,device)).isEqualTo(1);
    }
    @Test void globalSwitchAndOfflineStateBlockBothViewAndNewCommands() throws Exception {
        start(); properties.setEnabled(false);
        try {
            var data=json.readTree(call("GET","/api/devices",elder,null,200).getResponse().getContentAsString()).path("data").get(0);
            assertThat(data.path("controlable").asBoolean()).isFalse();
            call("POST","/api/devices/"+device+"/commands",elder,"{\"targetPowerStatus\":\"ON\"}",403);
        } finally {properties.setEnabled(true);}
        jdbcTemplate.update("UPDATE device SET last_seen_at=? WHERE id=?",LocalDateTime.now().minusMinutes(1),device);
        var data=json.readTree(call("GET","/api/devices",elder,null,200).getResponse().getContentAsString()).path("data").get(0);
        assertThat(data.path("controlable").asBoolean()).isFalse();
        call("POST","/api/devices/"+device+"/commands",elder,"{\"targetPowerStatus\":\"ON\"}",409);
    }
    @Test void disabledExpiredCommandIsClosedByScheduler() throws Exception {
        start(); Long c=submit(device,"expired",elder);
        jdbcTemplate.update("UPDATE device_command SET expires_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),c);
        call("PUT","/api/devices/"+device,admin,"{\"enabled\":false}",200);
        scheduler.runOnce(); assertThat(state(c)).isEqualTo("TIMED_OUT");
        assertThat(devices.selectById(device).getSimulatedPowerStatus()).isEqualTo("OFF");
    }
    @Test void stoppingAfterExecutionWithoutAckDoesNotClaimCancellation() throws Exception {
        start(); call("POST","/api/devices/"+device+"/simulation-mode",admin,"{\"mode\":\"NO_ACK\"}",200);
        Long c=submit(device,"noack-stop",elder); ready(c);executor.tick(device);
        call("POST","/api/devices/"+device+"/simulator/stop",admin,null,200);
        assertThat(state(c)).isEqualTo("PENDING");
        jdbcTemplate.update("UPDATE device_command SET expires_at=? WHERE id=?",LocalDateTime.now().minusSeconds(1),c);
        scheduler.runOnce();assertThat(state(c)).isEqualTo("TIMED_OUT");
        assertThat(devices.selectById(device).getSimulatedPowerStatus()).isEqualTo("ON");
    }
    @Test void idsBeyondJavascriptSafeIntegerAreStrings() throws Exception {
        long big=9007199254740993L;
        jdbcTemplate.update("UPDATE device SET id=? WHERE id=?",big,device);device=big;
        var row=json.readTree(call("GET","/api/devices",elder,null,200).getResponse().getContentAsString()).path("data").get(0);
        assertThat(row.path("id").isString()).isTrue();assertThat(row.path("id").asString()).isEqualTo(Long.toString(big));
        assertThat(row.path("elderId").isString()).isTrue();
        start();var c=json.readTree(call("POST","/api/devices/"+device+"/commands",elder,"{\"targetPowerStatus\":\"ON\",\"requestId\":\"large-id\"}",200).getResponse().getContentAsString()).path("data");
        assertThat(c.path("deviceId").isString()).isTrue();assertThat(c.path("id").isString()).isTrue();
        var choices=json.readTree(call("GET","/api/devices/assignable-elders",admin,null,200).getResponse().getContentAsString()).path("data");
        assertThat(choices.get(0).path("id").isString()).isTrue();
    }
    @Test void roundRobinDoesNotStarveDevicesPastBatchLimit() throws Exception {
        Long second=create("IT-P4-SECOND",owner);start();
        call("POST","/api/devices/"+second+"/simulator/start",admin,null,200);
        jdbcTemplate.update("UPDATE device SET last_seen_at=? WHERE id IN (?,?)",LocalDateTime.now().minusMinutes(1),device,second);
        int previous=properties.getBatchSize();properties.setBatchSize(1);
        try { scheduler.runOnce(); scheduler.runOnce();
            assertThat(devices.selectById(device).getLastSeenAt()).isAfter(LocalDateTime.now().minusSeconds(10));
            assertThat(devices.selectById(second).getLastSeenAt()).isAfter(LocalDateTime.now().minusSeconds(10));
        } finally {properties.setBatchSize(previous);}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"NORMAL","NO_ACK"})
    void stateAndReceiptRollbackTogetherIfRequiredReceiptWriteFails(String mode) throws Exception {
        start(); Long c=submit(device,"receipt-fail",elder);ready(c);
        call("POST","/api/devices/"+device+"/simulation-mode",admin,"{\"mode\":\""+mode+"\"}",200);
        if(mode.equals("NORMAL")) doReturn(0).when(commands).markSucceeded(eq(c),anyString(),any());
        else doReturn(0).when(commands).markExecutedWithoutAck(eq(c),any());
        Assertions.assertThrows(RuntimeException.class,()->executor.tick(device));
        assertThat(devices.selectById(device).getSimulatedPowerStatus()).isEqualTo("OFF");
        assertThat(state(c)).isEqualTo("PENDING");
        assertThat(commands.findById(c).getSimulatedExecutedAt()).isNull();
    }
    @Test void disabledDeviceCannotBeStartedAndDisabledOwnerCannotReceiveDevices() throws Exception {
        call("PUT","/api/devices/"+device,admin,"{\"enabled\":false}",200);
        call("POST","/api/devices/"+device+"/simulator/start",admin,null,409);
        assertThat(devices.selectById(device).getLastSeenAt()).isNull();
        assertThat(devices.selectById(device).getSimulatorRunning()).isFalse();
        jdbcTemplate.update("UPDATE sys_user SET enabled=0 WHERE id=?",userMapper.findByUsername("it_p4_other").getId());
        call("PUT","/api/devices/"+device,admin,"{\"elderId\":\""+newOwner+"\"}",400);
        assertThat(devices.selectById(device).getElderId()).isEqualTo(owner);
        call("POST","/api/devices",admin,"{\"deviceNo\":\"IT-P4-DISABLED-OWNER\",\"deviceName\":\"拒绝夹具\",\"deviceType\":\"LIGHT\",\"elderId\":\""+newOwner+"\"}",400);
    }
    @Test void concurrentSameKeyIsOneCommandAndDifferentKeysDoNotStack() throws Exception {
        start();var same=parallel(6,i->request(device,"same-key",elder));
        assertThat(same).allMatch(x->x.getResponse().getStatus()==200);
        assertThat(same.stream().map(this::id).distinct().count()).isEqualTo(1);
        call("POST","/api/devices/"+device+"/simulator/stop",admin,null,200);start();
        var distinct=parallel(6,i->request(device,"distinct-"+i,elder));
        assertThat(distinct.stream().filter(x->x.getResponse().getStatus()==200).count()).isEqualTo(1);
        assertThat(distinct.stream().filter(x->x.getResponse().getStatus()==409).count()).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_command WHERE device_id=?",Integer.class,device)).isEqualTo(2);
    }
    @Test void crossDeviceConcurrentKeyCollisionReturnsConflictInsteadOfServerError() throws Exception {
        start();Long second=create("IT-P4-COLLISION",owner);call("POST","/api/devices/"+second+"/simulator/start",admin,null,200);
        CyclicBarrier beforeInsert=new CyclicBarrier(2);
        DeviceCommandMapper real=sqlSession.getMapper(DeviceCommandMapper.class);
        doAnswer(inv->{Object found=real.findByRequest(inv.getArgument(0),inv.getArgument(1));if(found==null)beforeInsert.await(10,TimeUnit.SECONDS);return found;})
            .when(commands).findByRequest(anyLong(),eq("cross-device"));
        var results=parallel(2,i->request(i==0?device:second,"cross-device",elder));
        assertThat(results.stream().map(x->x.getResponse().getStatus()).sorted().toList()).containsExactly(200,409);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM device_command WHERE request_id='cross-device'",Integer.class)).isEqualTo(1);
    }
    MvcResult request(Long d,String key,String session) throws Exception {
        return mvc.perform(post("/api/devices/{id}/commands",d).header("scc-test-token","Bearer "+session)
            .contentType(MediaType.APPLICATION_JSON).content("{\"targetPowerStatus\":\"ON\",\"requestId\":\""+key+"\"}")).andReturn();
    }
    interface Request { MvcResult run(int i) throws Exception; }
    List<MvcResult> parallel(int count,Request request) throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(count);CountDownLatch start=new CountDownLatch(1);
        List<Future<MvcResult>> tasks=new ArrayList<>();
        try {for(int i=0;i<count;i++){int index=i;tasks.add(pool.submit(()->{start.await();return request.run(index);}));}
            start.countDown();List<MvcResult> result=new ArrayList<>();for(var t:tasks)result.add(t.get(20,TimeUnit.SECONDS));return result;
        } finally {start.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
    }
}
