package org.imnu.smartcommunitycare.device;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.device.mapper.DeviceMapper;
import org.imnu.smartcommunitycare.device.model.entity.Device;
import org.imnu.smartcommunitycare.device.model.enums.SimulationMode;
import org.imnu.smartcommunitycare.device.simulation.DeviceSimulatorExecutor;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备档案与模拟控制测试。
 *
 * <p><b>调度不在测试里自动跑</b>：测试启用模拟器但调度间隔为一小时，
 * {@code DeviceSimulatorScheduler} 注册但本类运行期间不触发定时轮询，
 * 模拟执行的时机由用例显式调用 {@code executor.tick(deviceId)} 控制。
 * 让定时任务自己跑，「提交后何时执行」就变成竞态——
 * 用例刚提交完就断言 SUCCEEDED，偶尔过偶尔不过。
 *
 * <p>查状态一律用 jdbcTemplate 直查数据库，不用 JSON：
 * 接口返回的字段可能是转换后的值，字段是否真被写库只有查库能证明。
 */
@AutoConfigureMockMvc
class DeviceSimulationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeviceMapper deviceMapper;

    @Autowired
    private DeviceSimulatorExecutor executor;

    private static final String HEADER = "scc-test-token";

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?");

    private String adminToken;
    private String elderToken;
    private String otherElderToken;
    private String staffToken;
    private Long elderProfileId;
    private Long deviceId;

    @BeforeEach
    void setUp() throws Exception {
        createAccount("it_dev_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        elderProfileId = createElder("it_dev_elder");
        createElder("it_dev_other");
        createAccount("it_dev_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);

        adminToken = loginAndGetToken(mockMvc, "it_dev_admin", ADMIN_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, "it_dev_elder", ELDER_PASSWORD);
        otherElderToken = loginAndGetToken(mockMvc, "it_dev_other", ELDER_PASSWORD);
        staffToken = loginAndGetToken(mockMvc, "it_dev_staff", STAFF_PASSWORD);

        deviceId = createDevice("IT-LIGHT-01", "测试电灯", elderProfileId);
    }

    // ==================== 设备档案 ====================

    @Test
    void elderCanRunOwnSimulatorButCannotRunOthersOrChangeFaultMode() throws Exception {
        mockMvc.perform(post("/api/devices/{id}/simulator/start", deviceId)
                        .header(HEADER,"Bearer "+otherElderToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/devices/{id}/simulator/start", deviceId)
                        .header(HEADER,"Bearer "+staffToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/devices/{id}/simulator/start", deviceId)
                        .header(HEADER,"Bearer "+elderToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.simulatorRunning").value(true))
                .andExpect(jsonPath("$.data.controlable").value(true));
        mockMvc.perform(post("/api/devices/{id}/simulation-mode", deviceId)
                        .header(HEADER,"Bearer "+elderToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"NORMAL\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/devices/{id}/simulator/stop", deviceId)
                        .header(HEADER,"Bearer "+elderToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.simulatorRunning").value(false));
    }

    @Test
    @DisplayName("新建设备固定为模拟接入，且不自动启动模拟器")
    void createDeviceIsSimulatedAndIdle() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/devices")
                        .header(HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceNo":"IT-NEW-01","deviceName":"新建电灯","deviceType":"LIGHT",
                                 "location":"客厅","elderId":%d}
                                """.formatted(elderProfileId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessMode").value("SIMULATED"))
                .andExpect(jsonPath("$.data.simulatorRunning").value(false))
                .andReturn();

        Long newId = idOf(result);
        // 首启之前不应有上报状态：填 OFF 会让「还没报告过」被误读成「已关闭」
        Assertions.assertEquals("<NULL>", reportedPower(newId));
    }

    @Test
    @DisplayName("设备编号重复被拒")
    void duplicateDeviceNoRejected() throws Exception {
        mockMvc.perform(post("/api/devices")
                        .header(HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceNo":"IT-LIGHT-01","deviceName":"重号","deviceType":"LIGHT"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("老人不能建档，也不能启停他人设备")
    void nonAdminCannotManage() throws Exception {
        mockMvc.perform(post("/api/devices")
                        .header(HEADER, "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"deviceNo":"IT-NO-01","deviceName":"越权","deviceType":"LIGHT"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/devices/{id}/simulator/start", deviceId)
                        .header(HEADER, "Bearer " + otherElderToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("服务人员不能查看设备档案")
    void staffCannotViewDevices() throws Exception {
        mockMvc.perform(get("/api/devices").header(HEADER, "Bearer " + staffToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("老人只看自己的设备；访问他人设备返回 404 而非 403")
    void elderOnlySeesOwnDevices() throws Exception {
        mockMvc.perform(get("/api/devices").header(HEADER, "Bearer " + elderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceNo").value("IT-LIGHT-01"));

        // 404 而不是 403：403 会泄露「这台设备存在且属于别人」
        mockMvc.perform(post("/api/devices/{id}/commands", deviceId)
                        .header(HEADER, "Bearer " + otherElderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetPowerStatus":"ON"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("停用设备会让待执行指令失效，不让模拟器继续执行")
    void disablingDeviceCancelsPendingCommands() throws Exception {
        startSimulator(deviceId);
        Long commandId = submitCommand(deviceId, "ON");

        mockMvc.perform(put("/api/devices/{id}", deviceId)
                        .header(HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.controlable").value(false))
                .andExpect(jsonPath("$.data.controlBlockedReason").value("设备已停用"));

        executor.tick(deviceId);

        Assertions.assertEquals("CANCELLED", commandStatus(commandId));
    }

    // ==================== NORMAL 场景 ====================

    @Test
    @DisplayName("NORMAL：指令到期前不执行，到期后执行成功并上报状态")
    void normalScenarioExecutesAfterReadyTime() throws Exception {
        startSimulator(deviceId);
        Long commandId = submitCommand(deviceId, "ON");

        // 未到 readyAt：不能执行，设备状态不变
        executor.tick(deviceId);
        Assertions.assertEquals("PENDING", commandStatus(commandId));
        Assertions.assertEquals("OFF", simulatedPower(deviceId));

        // 把 readyAt 提前，模拟「演示延迟已过」
        makeReady(commandId);
        executor.tick(deviceId);

        Assertions.assertEquals("SUCCEEDED", commandStatus(commandId));
        Assertions.assertEquals("ON", simulatedPower(deviceId));
        // 上报状态按心跳周期走，不会因为指令执行就立刻刷新。
        // 这一点是设计：模拟上报是一个独立周期的事件，
        // 把两者绑在一起反而会让「设备已执行但还没上报」这个状态无法出现。
        Assertions.assertEquals("OFF", reportedPower(deviceId));

        // 把 last_seen_at 推到心跳间隔之前，下一轮 tick 就会带新状态上报
        jdbcTemplate.update("UPDATE device SET last_seen_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(1), deviceId);
        executor.tick(deviceId);
        Assertions.assertEquals("ON", reportedPower(deviceId));
    }

    // ==================== FAIL_NEXT 场景 ====================

    @Test
    @DisplayName("FAIL_NEXT：本次失败，开关不变，场景自动恢复 NORMAL")
    void failNextFailsOnceAndRestores() throws Exception {
        startSimulator(deviceId);
        setMode(deviceId, SimulationMode.FAIL_NEXT);

        Long firstId = submitCommand(deviceId, "ON");
        makeReady(firstId);
        executor.tick(deviceId);

        Assertions.assertEquals("FAILED", commandStatus(firstId));
        // 失败的关键证据：开关没变
        Assertions.assertEquals("OFF", simulatedPower(deviceId));
        Assertions.assertEquals("NORMAL", modeOf(deviceId));

        // 一次性：下一条指令正常执行
        Long secondId = submitCommand(deviceId, "ON");
        makeReady(secondId);
        executor.tick(deviceId);

        Assertions.assertEquals("SUCCEEDED", commandStatus(secondId));
        Assertions.assertEquals("ON", simulatedPower(deviceId));
    }

    // ==================== NO_ACK 场景 ====================

    @Test
    @DisplayName("NO_ACK：设备已改变开关但不确认，指令到期落 TIMED_OUT")
    void noAckExecutesOnceThenTimesOut() throws Exception {
        startSimulator(deviceId);
        setMode(deviceId, SimulationMode.NO_ACK);

        Long commandId = submitCommand(deviceId, "ON");
        makeReady(commandId);
        executor.tick(deviceId);

        // 设备侧已改，指令仍是 PENDING —— 这就是「结果未确认」的真实数据形态
        Assertions.assertEquals("ON", simulatedPower(deviceId));
        Assertions.assertEquals("PENDING", commandStatus(commandId));
        // 上报状态未变：设备改了自己的状态，但没向平台报告
        Assertions.assertEquals("OFF", reportedPower(deviceId));

        // 多跑几轮不应重复执行：simulated_executed_at 已写
        LocalDateTime firstExecuted = executedAt(commandId);
        for (int i = 0; i < 3; i++) {
            executor.tick(deviceId);
        }
        Assertions.assertEquals(firstExecuted, executedAt(commandId));

        // 到期收口
        expireNow(commandId);
        executor.tick(deviceId);
        Assertions.assertEquals("TIMED_OUT", commandStatus(commandId));
    }

    @Test
    @DisplayName("TIMED_OUT 后不会被补执行：结果未知不等于没执行")
    void expiredCommandIsNotExecutedLater() throws Exception {
        startSimulator(deviceId);
        Long commandId = submitCommand(deviceId, "ON");

        // 直接把 ready_at 与 expires_at 都推到过去：模拟「调度没赶上，指令已过期」
        jdbcTemplate.update(
                "UPDATE device_command SET ready_at = ?, expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(5),
                LocalDateTime.now().minusMinutes(4),
                commandId);
        executor.tick(deviceId);

        Assertions.assertEquals("TIMED_OUT", commandStatus(commandId));
        Assertions.assertEquals("OFF", simulatedPower(deviceId));
    }

    // ==================== 幂等与防叠加 ====================

    @Test
    @DisplayName("同一 requestId 重复提交返回同一条指令，不重复下发")
    void sameRequestIdIsIdempotent() throws Exception {
        startSimulator(deviceId);
        String requestId = "req-" + UUID.randomUUID();

        Long first = submitCommand(deviceId, "ON", requestId);
        Long second = submitCommand(deviceId, "ON", requestId);

        Assertions.assertEquals(first, second);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_command WHERE device_id = ?", Integer.class, deviceId);
        Assertions.assertEquals(1, count);
    }

    @Test
    @DisplayName("同一 requestId 换了内容按冲突拒绝，不静默返回旧指令")
    void sameRequestIdDifferentContentRejected() throws Exception {
        startSimulator(deviceId);
        String requestId = "req-" + UUID.randomUUID();

        submitCommand(deviceId, "ON", requestId);

        mockMvc.perform(post("/api/devices/{id}/commands", deviceId)
                        .header(HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetPowerStatus":"OFF","requestId":"%s"}
                                """.formatted(requestId)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("同一设备有未确认指令时不再叠加下发")
    void doesNotStackCommands() throws Exception {
        startSimulator(deviceId);
        submitCommand(deviceId, "ON");

        mockMvc.perform(post("/api/devices/{id}/commands", deviceId)
                        .header(HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetPowerStatus":"OFF"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("模拟器未启动时不能下发指令")
    void cannotControlWhenSimulatorStopped() throws Exception {
        mockMvc.perform(post("/api/devices/{id}/commands", deviceId)
                        .header(HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targetPowerStatus":"ON"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("停止模拟器后，待执行指令立即收口而不是永远停住")
    void stoppingSimulatorClosesPendingCommands() throws Exception {
        startSimulator(deviceId);
        Long commandId = submitCommand(deviceId, "ON");

        mockMvc.perform(post("/api/devices/{id}/simulator/stop", deviceId)
                        .header(HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.simulatorRunning").value(false));

        Assertions.assertEquals("CANCELLED", commandStatus(commandId));
    }

    // ==================== 在线判定 ====================

    @Test
    @DisplayName("超过离线阈值未上报即判离线，停用设备一律显示 DISABLED")
    void offlineThresholdApplies() throws Exception {
        startSimulator(deviceId);
        executor.tick(deviceId);

        mockMvc.perform(get("/api/devices").header(HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onlineStatus").value("ONLINE"));

        // 把最后心跳推到阈值之前
        jdbcTemplate.update("UPDATE device SET last_seen_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(10), deviceId);

        mockMvc.perform(get("/api/devices").header(HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onlineStatus").value("OFFLINE"));

        // 停用优先于在线判定：不与在线状态混在同一个字段里
        jdbcTemplate.update("UPDATE device SET enabled = 0 WHERE id = ?", deviceId);
        mockMvc.perform(get("/api/devices").header(HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].onlineStatus").value("DISABLED"));
    }

    @Test
    @DisplayName("设备视图带老人姓名，来自 sys_user.real_name 而非档案表")
    void viewCarriesElderName() throws Exception {
        mockMvc.perform(get("/api/devices").header(HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].elderName").value("测试-it_dev_elder"));
    }

    // ==================== 夹具 ====================

    private void startSimulator(Long id) throws Exception {
        mockMvc.perform(post("/api/devices/{id}/simulator/start", id)
                        .header(HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.simulatorRunning").value(true));
    }

    private void setMode(Long id, SimulationMode mode) throws Exception {
        mockMvc.perform(post("/api/devices/{id}/simulation-mode", id)
                        .header(HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"%s"}
                                """.formatted(mode.name())))
                .andExpect(status().isOk());
    }

    private Long submitCommand(Long id, String target) throws Exception {
        return submitCommand(id, target, null);
    }

    private Long submitCommand(Long id, String target, String requestId) throws Exception {
        String body = requestId == null
                ? """
                {"targetPowerStatus":"%s"}
                """.formatted(target)
                : """
                {"targetPowerStatus":"%s","requestId":"%s"}
                """.formatted(target, requestId);
        MvcResult result = mockMvc.perform(post("/api/devices/{id}/commands", id)
                        .header(HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result);
    }

    /** 把指令的允许执行时刻提前，模拟「演示延迟已过」 */
    private void makeReady(Long commandId) {
        jdbcTemplate.update("UPDATE device_command SET ready_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), commandId);
    }

    private void expireNow(Long commandId) {
        jdbcTemplate.update("UPDATE device_command SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), commandId);
    }

    private Long createDevice(String no, String name, Long elderProfile) {
        LocalDateTime now = LocalDateTime.now();
        Device device = new Device();
        device.setDeviceNo(no);
        device.setDeviceName(name);
        device.setDeviceType("LIGHT");
        device.setElderId(elderProfile);
        device.setAccessMode("SIMULATED");
        device.setLocation("客厅");
        device.setEnabled(true);
        device.setSimulatorRunning(false);
        device.setSimulationMode(SimulationMode.NORMAL.name());
        device.setSimulatedPowerStatus("OFF");
        device.setPowerStatus(null);
        device.setLastSeenAt(null);
        device.setVersion(1);
        device.setCreatedAt(now);
        device.setUpdatedAt(now);
        deviceMapper.insert(device);
        return device.getId();
    }

    private String commandStatus(Long commandId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM device_command WHERE id = ?", String.class,
                commandId);
    }

    private String simulatedPower(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT simulated_power_status FROM device WHERE id = ?", String.class, id);
    }

    private String reportedPower(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT IFNULL(power_status,'<NULL>') FROM device WHERE id = ?", String.class, id);
    }

    private String modeOf(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT simulation_mode FROM device WHERE id = ?", String.class, id);
    }

    private LocalDateTime executedAt(Long commandId) {
        return jdbcTemplate.queryForObject(
                "SELECT simulated_executed_at FROM device_command WHERE id = ?",
                LocalDateTime.class, commandId);
    }

    /**
     * 从响应体的 data.id 取值，用正则避开序列化库差异。
     *
     * <p>显式用 UTF-8 解码而不是 {@code getContentAsString()}：
     * 后者按响应头里的 charset 解码，缺省会退到 ISO-8859-1，
     * 中文响应内容会被读成乱码（Boot 4 起该方法还要求处理异常）。
     */
    private Long idOf(MvcResult result) {
        String body = new String(result.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        Matcher m = ID_PATTERN.matcher(body);
        if (!m.find()) {
            throw new IllegalStateException("响应中未找到 id：" + body);
        }
        return Long.valueOf(m.group(1));
    }
}
