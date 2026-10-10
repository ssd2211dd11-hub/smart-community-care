package org.imnu.smartcommunitycare.catalog;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.service.CatalogService;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务目录与时段测试。
 *
 * <p>重点验证时段的三条硬约束（V1.2 §4.5）：
 * <ul>
 *   <li>同一服务的时段不可重叠（首尾相接不算）</li>
 *   <li>容量不得低于已占用数</li>
 *   <li>开始时间必须晚于当前</li>
 * </ul>
 */
@AutoConfigureMockMvc
class CatalogTest extends BaseIntegrationTest {
    @Test
    void servicePaginationKeepsFilteredTotalAndAllRequiresAdmin() throws Exception {
        String token=newAdminToken();
        createFixtureItem(it->it.setName("V13分页目录一"));
        createFixtureItem(it->{it.setName("V13分页目录二");it.setEnabled(false);});
        mockMvc.perform(get("/api/services/paged").header("scc-test-token","Bearer "+token).param("keyword","V13分页目录").param("all","true").param("pageSize","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records.length()").value(1));
        mockMvc.perform(get("/api/services/paged").header("scc-test-token","Bearer "+token).param("keyword","V13分页目录").param("all","true").param("pageNum","2147483647"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.records.length()").value(0));
        String elder=newElderToken();
        mockMvc.perform(get("/api/services/paged").header("scc-test-token","Bearer "+elder).param("keyword","V13分页目录"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/services/paged").header("scc-test-token","Bearer "+elder).param("all","true")).andExpect(status().isForbidden());
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /**
     * 从响应中提取身份ID，兼容带引号的字符串及数字形态。
     */
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ElderProfileMapper elderProfileMapper;

    /** 管理员账号序号，保证用户名合法（^[A-Za-z0-9_]{3,50}$） */
    private int adminSeq = 0;

    /** 老人账号序号 */
    private int elderSeq = 0;

    /** 最近一次创建的管理员令牌 */
    private String admin;

    /** 登录一个新建的管理员账号，返回令牌 */
    private String newAdminToken() throws Exception {
        String username = "it_cat_adm_" + (++adminSeq);
        createAccount(username, ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        return loginAndGetToken(mockMvc, username, ADMIN_PASSWORD);
    }

    /** 登录一个新建的老人账号（含档案），返回令牌 */
    private String newElderToken() throws Exception {
        String username = "it_cat_elder_" + (++elderSeq);
        Long userId = createAccount(username, ELDER_PASSWORD, UserRole.ELDER, true);
        ElderProfile profile = new ElderProfile();
        profile.setUserId(userId);
        profile.setBirthDate(LocalDate.of(1960, 1, 1));
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        elderProfileMapper.insert(profile);
        return loginAndGetToken(mockMvc, username, ELDER_PASSWORD);
    }

    /**
     * 创建一个服务，并把管理员令牌存到 {@link #admin}。
     *
     * <p>建服务需要管理员身份，所以内部先登录一次；后续请求复用该令牌，
     * 避免每个用例重复写登录代码。
     */
    private Long createItem(String name) throws Exception {
        admin = newAdminToken();
        ServiceItem item = new ServiceItem();
        item.setName(name);
        item.setCategory("HOUSEKEEPING");
        item.setDescription("测试服务");
        item.setPrice(new BigDecimal("80.00"));
        item.setProvider("测试提供方");

        // 走 HTTP 接口创建而不是直接调 Service：
        // createItem 内部校验管理员身份，而测试代码不在请求上下文里，
        // 直接调会抛 SaTokenContextException。走接口同时验证了权限与参数绑定。
        String created = mockMvc.perform(post("/api/services")
                        .header("scc-test-token", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","category":"HOUSEKEEPING","description":"IT-FIXTURE",
                                 "price":80.00,"provider":"夹具提供方"}
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 走 HTTP 接口拿不到实体，但清理靠 description 里的 IT-FIXTURE 标记识别，
        // 因此这里无需登记 ID——createFixtureItems() 每次清理前会扫库登记
        return extractId(created);
    }

    /** 明天某时刻，用于造未来的时段 */
    private static LocalDateTime tomorrow(int hour, int minute) {
        return LocalDate.now().plusDays(1).atTime(LocalTime.of(hour, minute));
    }

    /** 建一个时段并返回其 ID */
    private Long createSlot(Long serviceId, int startHour, int startMinute,
                            int endHour, int endMinute, int capacity) throws Exception {
        String created = mockMvc.perform(post("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("startAt", tomorrow(startHour, startMinute).format(FMT))
                        .param("endAt", tomorrow(endHour, endMinute).format(FMT))
                        .param("capacity", String.valueOf(capacity)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return extractId(created);
    }

    // ---------- 服务项目 ----------

    @Test
    @DisplayName("管理员创建服务后出现在目录中")
    void adminCanCreateService() throws Exception {
        createItem("日常保洁");

        // 断言「我建的服务出现在目录里」，而不是「目录里只有一个服务」。
        // 清理只删夹具，测试库里可能存在有意保留的非夹具数据，
        // 断言列表长度等于 1 是清理全表删除时代的遗留假设。
        mockMvc.perform(get("/api/services")
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.name=='日常保洁')]").isNotEmpty());
    }

    @Test
    @DisplayName("下架服务后不再出现在目录中，但记录保留")
    void disabledServiceHiddenFromCatalog() throws Exception {
        Long serviceId = createItem("康复理疗");

        mockMvc.perform(put("/api/services/{id}/disable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());

        // 同上：断言「这个服务不在列表里」，而不是「列表是空的」
        mockMvc.perform(get("/api/services")
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(jsonPath("$.data[?(@.name=='康复理疗')]").doesNotExist());

        // 记录仍在库里，只是下架
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_item WHERE id = ?", Long.class, serviceId))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("老人不能创建服务")
    void elderCannotCreateService() throws Exception {
        String elder = newElderToken();

        mockMvc.perform(post("/api/services")
                        .header("scc-test-token", "Bearer " + elder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"非法服务","category":"HOUSEKEEPING"}
                                """))
                .andExpect(status().isForbidden());
    }

    // ---------- 时段约束 ----------

    @Test
    @DisplayName("开始时间必须晚于当前时间")
    void slotStartMustBeFuture() throws Exception {
        Long serviceId = createItem("时段校验一");

        LocalDateTime past = LocalDateTime.now().minusHours(1);
        mockMvc.perform(post("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("startAt", past.format(FMT))
                        .param("endAt", past.plusHours(1).format(FMT))
                        .param("capacity", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("时段开始时间必须晚于当前时间"));
    }

    @Test
    @DisplayName("结束时间必须晚于开始时间")
    void slotEndMustBeAfterStart() throws Exception {
        Long serviceId = createItem("时段校验二");

        mockMvc.perform(post("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("startAt", tomorrow(10, 0).format(FMT))
                        .param("endAt", tomorrow(9, 0).format(FMT))
                        .param("capacity", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("结束时间必须晚于开始时间"));
    }

    @Test
    @DisplayName("同一服务的时段不可重叠")
    void overlappingSlotsRejected() throws Exception {
        Long serviceId = createItem("时段校验三");
        createSlot(serviceId, 9, 0, 12, 0, 2);

        // 与上一个部分重叠
        mockMvc.perform(post("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("startAt", tomorrow(11, 0).format(FMT))
                        .param("endAt", tomorrow(14, 0).format(FMT))
                        .param("capacity", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("该时段与已有时段重叠"));
    }

    @Test
    @DisplayName("首尾相接的时段不算重叠")
    void adjacentSlotsAllowed() throws Exception {
        Long serviceId = createItem("时段校验四");
        createSlot(serviceId, 9, 0, 12, 0, 2);

        // 上一段 12:00 结束，下一段 12:00 开始——是连续排班，不是重叠
        mockMvc.perform(post("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("startAt", tomorrow(12, 0).format(FMT))
                        .param("endAt", tomorrow(15, 0).format(FMT))
                        .param("capacity", "2"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("不同服务的时段可以重叠")
    void differentServicesMayOverlap() throws Exception {
        Long serviceA = createItem("时段校验五甲");
        createSlot(serviceA, 9, 0, 12, 0, 2);

        // 换一个管理员再建另一个服务的同一时段——两人同一时段各带一位老人是正常的
        Long serviceB = createItem("时段校验五乙");
        createSlot(serviceB, 9, 0, 12, 0, 2);
    }

    @Test
    @DisplayName("容量必须为正整数")
    void capacityMustBePositive() throws Exception {
        Long serviceId = createItem("时段校验六");

        mockMvc.perform(post("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("startAt", tomorrow(9, 0).format(FMT))
                        .param("endAt", tomorrow(12, 0).format(FMT))
                        .param("capacity", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("容量必须为正整数"));
    }

    // ---------- 容量与关闭 ----------

    @Test
    @DisplayName("容量不得调整到低于已占用数")
    void capacityCannotDropBelowOccupied() throws Exception {
        Long serviceId = createItem("容量校验");
        Long slotId = createSlot(serviceId, 9, 0, 12, 0, 3);

        // 人为把已占用数改成 2，模拟已有两单
        jdbcTemplate.update("UPDATE service_slot SET occupied_count = 2 WHERE id = ?", slotId);

        mockMvc.perform(put("/api/services/slots/{id}/capacity", slotId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("capacity", "1"))
                .andExpect(status().isConflict());

        // 调到不低于已占用是允许的
        mockMvc.perform(put("/api/services/slots/{id}/capacity", slotId)
                        .header("scc-test-token", "Bearer " + admin)
                        .param("capacity", "2"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("关闭时段后不再出现在老人可见的开放时段里")
    void closedSlotHiddenFromElder() throws Exception {
        Long serviceId = createItem("关闭校验");
        Long slotId = createSlot(serviceId, 9, 0, 12, 0, 2);
        String elder = newElderToken();

        // 关闭前老人能看到
        mockMvc.perform(get("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + elder))
                .andExpect(jsonPath("$.data.length()").value(1));

        mockMvc.perform(put("/api/services/slots/{id}/close", slotId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(status().isOk());

        // 关闭后不再提供预约
        mockMvc.perform(get("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + elder))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("过去的时段不出现在开放列表中")
    void pastSlotsNotListed() throws Exception {
        Long serviceId = createItem("时段过滤");

        // 直接写库造一个过去的时段（创建接口不允许）
        jdbcTemplate.update("""
                INSERT INTO service_slot (service_id, start_at, end_at, capacity, occupied_count, enabled)
                VALUES (?, DATE_SUB(NOW(3), INTERVAL 2 DAY),
                        DATE_SUB(NOW(3), INTERVAL 1 DAY), 2, 0, 1)
                """, serviceId);

        mockMvc.perform(get("/api/services/{id}/slots", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("未登录不能浏览服务目录")
    void anonymousCannotBrowse() throws Exception {
        mockMvc.perform(get("/api/services"))
                .andExpect(status().isUnauthorized());
    }

    /** 从响应 JSON 中取 id（正则，不依赖序列化库 API） */
    private Long extractId(String json) {
        Matcher m = ID_PATTERN.matcher(json);
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }
}
