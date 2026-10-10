package org.imnu.smartcommunitycare.catalog;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证服务上下架、时段开放与关闭可双向操作。
 * 已有订单、时段时间与占用保持不变；老人只查询可预约的目录。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogBidirectionalTest extends BaseIntegrationTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    private String admin;
    private String elder;
    private Long serviceId;
    private Long slotId;
    private LocalDateTime slotStart;
    private LocalDateTime slotEnd;

    @BeforeEach
    void prepare() throws Exception {
        createAccount("it_bidi_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        admin = loginAndGetToken(mockMvc, "it_bidi_admin", ADMIN_PASSWORD);
        createElder("it_bidi_elder", ELDER_PASSWORD);
        elder = loginAndGetToken(mockMvc, "it_bidi_elder", ELDER_PASSWORD);

        serviceId = createFixtureItem(it -> {
            it.setName("双向操作测试服务");
            it.setDescription("用于验证上架与时段重新开放");
            it.setPrice(new BigDecimal("60.00"));
            it.setPhone("13400000000");
        });

        slotStart = LocalDateTime.now().plusDays(2).withHour(9).withMinute(0);
        slotEnd = LocalDateTime.now().plusDays(2).withHour(12).withMinute(0);
        mockMvc.perform(post("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("startAt", slotStart.format(FMT))
                        .param("endAt", slotEnd.format(FMT))
                        .param("capacity", "4"))
                .andExpect(status().isOk());
        slotId = jdbcTemplate.queryForObject(
                "SELECT id FROM service_slot WHERE service_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, serviceId);

        // 起止时间基准从库里读，**不用上面构造的入参**。
        //
        // 第三次踩同一个坑：入参带纳秒（09:00:30.185818200），
        // 而 service_slot 的列是 DATETIME，落库只剩整秒（09:00:30）。
        // 拿入参当基准，每次都会报「时间被改变」，而实际没有任何操作改过它。
        slotStart = slotStartAt();
        slotEnd = slotEndAt();
    }

    // ==================== 服务：下架 → 上架 ====================

    @Test
    @DisplayName("服务下架后可重新上架，且管理端能看到下架项")
    void serviceCanBeDisabledThenEnabled() throws Exception {
        // 初始：上架，管理端与老人目录都看得到
        assertThat(enabledOf(serviceId)).isTrue();
        assertThat(idsInAdminList()).contains(serviceId);
        assertThat(idsInElderList()).contains(serviceId);

        // 下架
        mockMvc.perform(put("/api/services/{id}/disable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(enabledOf(serviceId)).as("下架后 enabled 应为 false").isFalse();

        // 关键：下架后老人目录看不到，但**管理端仍能看到**——
        // 否则管理员无法恢复，这正是本批要修的缺口
        assertThat(idsInElderList()).as("下架后老人目录不应包含该服务").doesNotContain(serviceId);
        assertThat(idsInAdminList())
                .as("下架后管理端仍应能看到该服务，否则无法上架回来")
                .contains(serviceId);

        // 上架
        mockMvc.perform(put("/api/services/{id}/enable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(enabledOf(serviceId)).as("上架后 enabled 应为 true").isTrue();

        // 上架后老人目录恢复可见
        assertThat(idsInElderList()).as("上架后老人目录应重新可见").contains(serviceId);
    }

    @Test
    @DisplayName("重复上架是幂等的，不报错也不改变其它字段")
    void enablingTwiceIsIdempotent() throws Exception {
        mockMvc.perform(put("/api/services/{id}/disable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/services/{id}/enable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());
        // 第二次：已上架，应仍然 200
        mockMvc.perform(put("/api/services/{id}/enable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());

        assertThat(enabledOf(serviceId)).isTrue();
        // 幂等不等于「顺手改了别的」：名称与价格必须原样
        ServiceItem item = itemOf(serviceId);
        assertThat(item.getName()).isEqualTo("双向操作测试服务");
        assertThat(item.getPrice()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("下架再上架不取消已有订单，也不改订单快照")
    void disableThenEnableKeepsExistingOrders() throws Exception {
        String orderId = bookOne();
        assertThat(orderId).isNotBlank();

        String snapshotBefore = orderField(orderId, "service_name_snapshot");
        String priceBefore = orderField(orderId, "price_snapshot");
        String statusBefore = orderField(orderId, "status");

        mockMvc.perform(put("/api/services/{id}/disable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/services/{id}/enable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());

        assertThat(orderField(orderId, "status"))
                .as("上下架往返不得改变订单状态").isEqualTo(statusBefore);
        assertThat(orderField(orderId, "service_name_snapshot"))
                .as("订单的服务名快照不得被当前目录资料覆盖").isEqualTo(snapshotBefore);
        assertThat(orderField(orderId, "price_snapshot"))
                .as("订单的价格快照不得被当前目录资料覆盖").isEqualTo(priceBefore);
        assertThat(occupied()).as("上下架往返不得释放名额").isEqualTo(1);
        assertThat(logCount(orderId, "CANCEL"))
                .as("上下架往返不得产生取消日志").isZero();
    }

    // ==================== 时段：关闭 → 重新开放 ====================

    @Test
    @DisplayName("时段关闭后可重新开放，且起止/容量/占用都不变")
    void slotCanBeClosedThenReopened() throws Exception {
        LocalDateTime startBefore = slotStartAt();
        LocalDateTime endBefore = slotEndAt();
        int capacityBefore = capacity();

        mockMvc.perform(put("/api/services/slots/{id}/close", slotId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());
        assertThat(slotEnabled()).as("关闭后 enabled 应为 false").isFalse();
        assertThat(idsInElderSlots()).as("关闭后老人不应看到该时段").doesNotContain(slotId);

        mockMvc.perform(put("/api/services/slots/{id}/reopen", slotId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());

        assertThat(slotEnabled()).as("重新开放后 enabled 应为 true").isTrue();
        // 三项不得被改动——这是「只翻转开关」的含义
        assertThat(slotStartAt()).as("重新开放不得改动开始时间").isEqualTo(startBefore);
        assertThat(slotEndAt()).as("重新开放不得改动结束时间").isEqualTo(endBefore);
        assertThat(capacity()).as("重新开放不得改动容量").isEqualTo(capacityBefore);
        assertThat(occupied()).as("重新开放不得改动已占用").isZero();
        assertThat(idsInElderSlots()).as("重新开放后老人应重新看到该时段").contains(slotId);
    }

    @Test
    @DisplayName("时段重新开放不取消已有订单，不改其快照")
    void reopenKeepsExistingOrders() throws Exception {
        String orderId = bookOne();
        assertThat(occupied()).isEqualTo(1);

        String startSnapBefore = orderField(orderId, "slot_start_snapshot");
        String endSnapBefore = orderField(orderId, "slot_end_snapshot");
        String statusBefore = orderField(orderId, "status");

        mockMvc.perform(put("/api/services/slots/{id}/close", slotId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/services/slots/{id}/reopen", slotId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());

        assertThat(orderField(orderId, "status"))
                .as("开关往返不得改变订单状态").isEqualTo(statusBefore);
        assertThat(orderField(orderId, "slot_start_snapshot"))
                .as("订单的开始时间快照不得被改写").isEqualTo(startSnapBefore);
        assertThat(orderField(orderId, "slot_end_snapshot"))
                .as("订单的结束时间快照不得被改写").isEqualTo(endSnapBefore);
        assertThat(occupied()).as("开关往返不得释放名额").isEqualTo(1);
        assertThat(logCount(orderId, "CANCEL")).isZero();
    }

    @Test
    @DisplayName("重复开放是幂等的，起止与占用不变")
    void reopeningTwiceIsIdempotent() throws Exception {
        mockMvc.perform(put("/api/services/slots/{id}/close", slotId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/services/slots/{id}/reopen", slotId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/services/slots/{id}/reopen", slotId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());

        assertThat(slotEnabled()).isTrue();
        assertThat(slotStartAt()).as("重复开放后开始时间仍应是库里的原值")
                .isEqualTo(slotStart);
        assertThat(occupied()).isZero();
    }

    // ==================== 权限：反向动作同样受控 ====================

    @Test
    @DisplayName("老人不能上架服务、不能开放时段、不能看已下架服务")
    void elderCannotPerformReverseAdminActions() throws Exception {
        mockMvc.perform(put("/api/services/{id}/disable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());

        // 已下架，老人用 all=true 也拿不到
        mockMvc.perform(get("/api/services").param("all", "true")
                        .header("scc-test-token", "Bearer " + elder))
                .andExpect(status().isForbidden());

        // 老人不能上架
        mockMvc.perform(put("/api/services/{id}/enable", serviceId)
                        .header("scc-test-token", "Bearer " + elder))
                .andExpect(status().isForbidden());

        // 老人不能开放时段
        mockMvc.perform(put("/api/services/slots/{id}/reopen", slotId)
                        .header("scc-test-token", "Bearer " + elder))
                .andExpect(status().isForbidden());

        assertThat(enabledOf(serviceId)).as("越权请求不得改变服务状态").isFalse();
    }

    @Test
    @DisplayName("未登录不能上架，也不能用 all=true 探查已下架服务")
    void anonymousCannotUseReverseActionsOrAllFlag() throws Exception {
        mockMvc.perform(put("/api/services/{id}/enable", serviceId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(put("/api/services/slots/{id}/reopen", slotId))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/services").param("all", "true"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("不存在的服务与时段：明确 404，不是 500")
    void reverseActionsOnMissingTargetReturn404() throws Exception {
        // 负向请求必须明确按业务规则拒绝，服务器异常不能算正确拒绝。
        mockMvc.perform(put("/api/services/{id}/enable", 99999999L)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/services/slots/{id}/reopen", 99999999L)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }

    // ==================== 辅助 ====================

    /** 老人真实建一单，返回订单 ID */
    private String bookOne() throws Exception {
        String resp = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"测试联系人",
                                 "contactPhone":"13900000007",
                                 "address":"双向操作测试地址","requestId":"%s"}
                                """.formatted(slotId, java.util.UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
        if (!m.find()) {
            throw new IllegalStateException("建单响应中未找到订单 ID：" + resp);
        }
        return m.group(1);
    }

    private ServiceItem itemOf(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT * FROM service_item WHERE id = ?",
                (rs, i) -> {
                    ServiceItem it = new ServiceItem();
                    it.setId(rs.getLong("id"));
                    it.setName(rs.getString("name"));
                    it.setPrice(rs.getBigDecimal("price"));
                    it.setEnabled(rs.getBoolean("enabled"));
                    return it;
                }, id);
    }

    private boolean enabledOf(Long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT enabled FROM service_item WHERE id = ?", Boolean.class, id));
    }

    private boolean slotEnabled() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT enabled FROM service_slot WHERE id = ?", Boolean.class, slotId));
    }

    private LocalDateTime slotStartAt() {
        return jdbcTemplate.queryForObject(
                "SELECT start_at FROM service_slot WHERE id = ?", LocalDateTime.class, slotId);
    }

    private LocalDateTime slotEndAt() {
        return jdbcTemplate.queryForObject(
                "SELECT end_at FROM service_slot WHERE id = ?", LocalDateTime.class, slotId);
    }

    private int capacity() {
        return jdbcTemplate.queryForObject(
                "SELECT capacity FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private int occupied() {
        return jdbcTemplate.queryForObject(
                "SELECT occupied_count FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private String orderField(String orderId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM service_order WHERE id = ?", String.class, orderId);
    }

    private int logCount(String orderId, String action) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_order_log WHERE order_id = ? AND action = ?",
                Integer.class, orderId, action);
        return n == null ? 0 : n;
    }

    /** 老人目录（默认，只含上架）里的服务 ID */
    private java.util.List<Long> idsInElderList() throws Exception {
        String raw = mockMvc.perform(get("/api/services")
                        .header("scc-test-token", "Bearer " + elder))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return serviceIdsIn(raw);
    }

    /** 管理端目录（all=true，含下架）里的服务 ID */
    private java.util.List<Long> idsInAdminList() throws Exception {
        return serviceIdsIn(mockMvc.perform(get("/api/services").param("all", "true")
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** 老人可见的该服务时段 ID */
    private java.util.List<Long> idsInElderSlots() throws Exception {
        return slotIdsIn(mockMvc.perform(get("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + elder))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private java.util.List<Long> serviceIdsIn(String json) {
        return idsIn(json, "id");
    }

    private java.util.List<Long> slotIdsIn(String json) {
        return idsIn(json, "id");
    }

    /**
     * 取出响应 {@code data} 数组里每一项的 {@code id}。
     *
     * <p><b>用 Jackson 解析，不用正则</b>——这是本项目第三次因正则解析 ID 失败：
     * <ol>
     *   <li>{@code OrderCrossElderTest} 首次用宽松正则，误把 slotId 当订单 ID；</li>
     *   <li>改成要求 <code>{"id"</code> 紧邻，结果一个都匹配不到
     *       （响应字段按字母序，{@code id} 前面还有 category / createdAt 等）；</li>
     *   <li>本类先加数组元素锚点 {@code [{ }、{@code ,{ }}，仍失败——
     *       因为<b>真正的难点不是数组前缀，是 {@code id} 不是对象的第一个字段</b>。</li>
     * </ol>
     * 正则要正确就得依赖字段顺序，而顺序不是契约、随时可能被序列化器改动。
     * {@code OrderCrossElderTest} 已改用 Jackson（Boot 4 的包名是
     * {@code tools.jackson}，不是 {@code com.fasterxml.jackson}），
     * 本类沿用同一做法。
     */
    private java.util.List<Long> idsIn(String json, String field) {
        JsonNode data = JSON.readTree(json).path("data");
        if (!data.isArray()) {
            throw new IllegalStateException("响应 data 不是数组，无法解析 " + field + "：" + json);
        }
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode item : data) {
            JsonNode idNode = item.path(field);
            // 身份 ID 的 JSON 契约是字符串（技术方案 §9.3），
            // 但仍兼容数字形态：断言的是「这个 ID 能被正确取到」，
            // 而不是「它必须是哪种 JSON 类型」——类型由 IdStringContractTest 守。
            if (idNode.isIntegralNumber()) {
                ids.add(idNode.asLong());
            } else if (idNode.isTextual() && idNode.asText().matches("\\d+")) {
                ids.add(Long.parseLong(idNode.asText()));
            } else {
                throw new IllegalStateException(
                        "数组元素缺少可解析的 " + field + "：" + item);
            }
        }
        return ids;
    }
}
