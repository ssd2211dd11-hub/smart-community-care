package org.imnu.smartcommunitycare.order;

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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 直接验证服务、时段、订单和人员候选的HTTP响应。
 * 身份ID使用JSON字符串，其他业务数值保留数字，避免仅凭序列化注解推断契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdStringContractTest extends BaseIntegrationTest {

    private static final String ADMIN = "it_idcontract_admin";
    private static final String ELDER = "it_idcontract_elder";
    private static final String STAFF = "it_idcontract_staff";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper slotMapper;

    private String adminToken;

    @BeforeEach
    void prepare() throws Exception {
        createAccount(ADMIN, ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        adminToken = loginAndGetToken(mockMvc, ADMIN, ADMIN_PASSWORD);
        createElder(ELDER, ELDER_PASSWORD);
        // 人员候选只返回「启用的服务人员」，没有这类账号时接口返回空数组。
        // 不准备夹具的话该用例会因「无从验证」而失败——那不是被测行为的问题。
        createAccount(STAFF, STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
    }

    /**
     * 服务的 {@code id} 必须是 JSON 字符串。
     *
     * <p>同时断言 {@code price} 仍是数字——把两者一起钉住，
     * 才能证明「只转了 ID，没有把整个实体变成字符串」。
     */
    @Test
    @DisplayName("服务 id 为 JSON 字符串，price 仍为数字")
    void serviceIdIsStringButPriceStaysNumber() throws Exception {
        String body = createServiceAndRead();

        assertQuoted(body, "id");
        assertBareNumber(body, "price");
    }

    /**
     * 时段的 {@code id} 与 {@code serviceId} 是字符串；
     * {@code capacity} 与 {@code occupiedCount} 仍是数字。
     */
    @Test
    @DisplayName("时段 id/serviceId 为字符串，capacity/occupiedCount 仍为数字")
    void slotIdsAreStringButCountersStayNumber() throws Exception {
        Long serviceId = createFixtureItem(it -> {
            it.setName("ID 契约服务");
            it.setDescription("用于验证时段 ID 序列化");
            it.setPrice(new BigDecimal("45.00"));
            it.setPhone("13400000000");
        });
        createSlot(serviceId, 4);

        String body = getJson("/api/services/" + serviceId + "/slots");

        assertQuoted(body, "id");
        assertQuoted(body, "serviceId");
        assertBareNumber(body, "capacity");
        assertBareNumber(body, "occupiedCount");
    }

    /**
     * 订单的五个身份 ID 都是字符串，而 {@code version} 与价格仍是数字。
     *
     * <p>{@code version} 尤其要单独断言：它参与并发控制，
     * 若被误转成字符串，前端的版本比较会静默失效。
     */
    @Test
    @DisplayName("订单 id/elderId/slotId/serviceId/staffId 为字符串，version 与价格仍为数字")
    void orderIdentityIdsAreStringButVersionStaysNumber() throws Exception {
        String elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);
        Long serviceId = createFixtureItem(it -> {
            it.setName("ID 契约订单服务");
            it.setDescription("用于验证订单 ID 序列化");
            it.setPrice(new BigDecimal("60.00"));
            it.setPhone("13400000001");
        });
        Long slotId = createSlot(serviceId, 3);

        String body = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000008",
                                 "address":"ID 契约测试地址","requestId":"%s"}
                                """.formatted(slotId, java.util.UUID.randomUUID().toString())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (String field : new String[]{"id", "elderId", "slotId", "serviceId"}) {
            assertQuoted(body, field);
        }
        // 未分派订单的 staffId 是 null。本项目的 Jackson 约定是
        // **省略 null 字段**（响应里没有 staffId 这个键），
        // 所以这里断言「不得出现字符串 "null"」——
        // 那才是真正的问题：null 被序列化成字符串后，前端会拿到 "null" 这个文本。
        assertThat(body).doesNotContain("\"staffId\":\"null\"");
        assertThat(body).doesNotContain("\"staffId\":null");

        assertBareNumber(body, "version");
        assertBareNumber(body, "priceSnapshot");
    }

    /**
     * 人员候选的 {@code id} 是字符串。
     *
     * <p>这个接口同时是敏感信息边界：只应含 id、姓名、角色。
     * 顺手确认它仍然最小化，避免改 ID 契约时顺手扩了字段。
     */
    @Test
    @DisplayName("人员候选 id 为字符串，且响应仍只含 id/姓名/角色")
    void assignableStaffIdIsStringAndStillMinimal() throws Exception {
        String body = getJson("/api/users/assignable-staff");

        if (body.contains("\"data\":[]")) {
            // 没有启用人员时该断言无意义，明确说明而不是静默通过
            throw new IllegalStateException(
                    "人员候选为空，无法验证 ID 契约；请先准备启用的服务人员夹具");
        }
        assertQuoted(body, "id");
        // 仍然不含敏感字段——ID 契约调整不应顺带扩大返回面
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("phone");
    }

    // ==================== 辅助 ====================

    private String createServiceAndRead() throws Exception {
        mockMvc.perform(post("/api/services")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"ID 契约服务","category":"HOUSEKEEPING",
                                 "description":"用于验证 ID 序列化 [IT-FIXTURE]","price":66.50,
                                 "provider":"契约测试方","phone":"13400000002"}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
        return getJson("/api/services");
    }

    private String getJson(String url) throws Exception {
        return mockMvc.perform(get(url)
                        .header("scc-test-token", "Bearer " + adminToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Long createSlot(Long serviceId, int capacity) throws Exception {
        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(serviceId);
        slot.setStartAt(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        slot.setEndAt(LocalDateTime.now().plusDays(1).withHour(12).withMinute(0));
        slot.setCapacity(capacity);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(slot);
        // Mapper 插入后由 MyBatis-Plus 回填主键，拿到的一定是本次那一行。
        // 不用 LAST_INSERT_ID()：它依赖「上条 update 与本条 select 同连接」，
        // 经连接池并不成立。
        if (slot.getId() == null) {
            throw new IllegalStateException("时段插入后未回填主键");
        }
        return slot.getId();
    }

    /** 断言某字段是带引号的数字（字符串形态） */
    private void assertQuoted(String json, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*\"(\\d+)\"");
        Matcher m = p.matcher(json);
        assertThat(m.find())
                .as("字段 %s 应为 JSON 字符串，实际响应：%s", field, abbreviate(json))
                .isTrue();
    }

    /** 断言某字段是不带引号的数字（数值形态） */
    private void assertBareNumber(String json, String field) {
        Pattern p = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");
        Matcher m = p.matcher(json);
        assertThat(m.find())
                .as("字段 %s 应保持 JSON 数字，实际响应：%s", field, abbreviate(json))
                .isTrue();
    }

    private String abbreviate(String s) {
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
