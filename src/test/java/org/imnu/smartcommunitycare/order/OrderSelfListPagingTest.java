package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证本人订单和任务的服务端分页、筛选总数、稳定排序及会话归属限制。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderSelfListPagingTest extends BaseIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String elderToken;
    private String otherElderToken;
    private String staffToken;
    private Long slotId;
    private Long serviceId;

    /** 本用例共造 7 笔，便于验证分页切片 */
    private static final int OWN_ORDERS = 7;

    @BeforeEach
    void prepare() throws Exception {
        createElder("it_page_elder", ELDER_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, "it_page_elder", ELDER_PASSWORD);
        createElder("it_page_other", ELDER_PASSWORD);
        otherElderToken = loginAndGetToken(mockMvc, "it_page_other", ELDER_PASSWORD);

        // 登记的工作人员账号
        createAccount("it_page_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        staffToken = loginAndGetToken(mockMvc, "it_page_staff", STAFF_PASSWORD);
        Long staffUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, "it_page_staff");

        serviceId = createFixtureItem(it -> {
            it.setName("本人列表分页测试服务");
            it.setDescription("用于验证本人列表分页");
            it.setPrice(new BigDecimal("30.00"));
            it.setPhone("13200000000");
        });
        slotId = createSlot(20);

        for (int i = 0; i < OWN_ORDERS; i++) {
            assertThat(bookAs(elderToken)).as("第 %d 笔建单应成功", i + 1).isNotBlank();
        }

        // 通过正常分派API建立本人任务，不直接修改分派状态。
        List<Long> myOrderIds = jdbcTemplate.queryForList(
                "SELECT id FROM service_order WHERE slot_id = ? ORDER BY id", Long.class, slotId);
        for (Long id : myOrderIds) {
            mockMvc.perform(post("/api/service-orders/{id}/assign", id)
                            .header("scc-test-token", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"staffId":%d,"reason":"分页测试准备","version":0}
                                    """.formatted(staffUserId)))
                    .andExpect(status().isOk());
        }
    }

    // ==================== 老人本人列表 ====================

    @Test
    @DisplayName("老人本人列表：服务端分页，pageSize 生效且 total 为全量数")
    void elderListIsServerSidePaged() throws Exception {
        JsonNode page = getPaged("/api/service-orders/mine/paged?pageNum=1&pageSize=3", elderToken);

        assertThat(page.path("records").size())
                .as("第1页应只返回 pageSize=3 条").isEqualTo(3);
        // total 必须是「该老人的全部订单数」，不是当前页条数。
        // 这一条能区分真分页与前端切片：前端切片拿不到 total
        assertThat(page.path("total").asLong())
                .as("total 应为该老人的全部订单数").isEqualTo(OWN_ORDERS);
        assertThat(page.path("pageNum").asInt()).isEqualTo(1);
        assertThat(page.path("pageSize").asInt()).isEqualTo(3);

        // 与「本人订单总数」对照，证明 total 不是接口自己编的。
        // 用 OWN_ORDERS 而不是「时段内订单数」：后者在别的用例里会包含
        // 另一位老人的订单，拿它当期望值等于把别人的也算成自己的。
        assertThat(page.path("total").asLong()).isEqualTo(OWN_ORDERS);
    }

    @Test
    @DisplayName("老人本人列表：翻页返回不同记录，且并集等于全部，无重复无遗漏")
    void elderPagingCoversAllWithoutDuplication() throws Exception {
        Set<Long> seen = new HashSet<>();
        int pages = 0;
        for (int pageNum = 1; pageNum <= 3; pageNum++) {
            JsonNode page = getPaged(
                    "/api/service-orders/mine/paged?pageNum=" + pageNum + "&pageSize=3", elderToken);
            for (JsonNode order : page.path("records")) {
                long id = order.path("id").asLong();
                assertThat(seen.add(id))
                        .as("订单 " + id + " 在第 " + pageNum + " 页重复出现——分页不稳定")
                        .isTrue();
            }
            pages++;
        }

        assertThat(pages).isEqualTo(3);
        assertThat(seen).as("翻完 3 页应覆盖全部订单").hasSize(OWN_ORDERS);
        // 每页最多 3 条，3 页最多 9 条 > 7，说明确实分过页
        assertThat(seen.size()).isGreaterThan(3);
    }

    @Test
    @DisplayName("老人本人列表：翻页换不掉归属，拿不到他人订单")
    void elderPagingCannotReachOtherElderOrders() throws Exception {
        // 另一位老人建一笔
        String otherOrderId = bookAs(otherElderToken);
        assertThat(otherOrderId).isNotBlank();

        // 无论翻多少页，都不应出现他人订单
        for (int pageNum = 1; pageNum <= 5; pageNum++) {
            JsonNode page = getPaged(
                    "/api/service-orders/mine/paged?pageNum=" + pageNum + "&pageSize=3", elderToken);
            for (JsonNode order : page.path("records")) {
                assertThat(order.path("id").asLong())
                        .as("第 " + pageNum + " 页出现了不属于本人的订单")
                        .isNotEqualTo(Long.parseLong(otherOrderId));
            }
        }

        // 显式传 elderId 之类的参数也不该被采纳——接口根本不接收它。
        // 期望值是**本人**订单数（OWN_ORDERS），不是整个时段的订单数：
        // 时段里还有另一位老人刚建的那笔，数它就等于把别人的也算成自己的。
        JsonNode withParam = getPaged(
                "/api/service-orders/mine/paged?pageNum=1&pageSize=20&elderId=99999", elderToken);
        assertThat(withParam.path("total").asLong())
                .as("传入 elderId 不应改变归属，仍只返回本人订单数")
                .isEqualTo(OWN_ORDERS);
    }

    @Test
    @DisplayName("老人本人列表：状态筛选在服务端生效，total 随之变化")
    void elderListFiltersByStatusOnServer() throws Exception {
        // 初始全是 ASSIGNED（prepare 里 7 笔都分派给登记工作人员了）
        JsonNode all = getPaged("/api/service-orders/mine/paged?pageNum=1&pageSize=20", elderToken);
        assertThat(all.path("total").asLong()).isEqualTo(OWN_ORDERS);
        assertThat(all.path("records").get(0).path("status").asText())
                .as("前置条件：prepare 已把订单全部分派，应为 ASSIGNED").isEqualTo("ASSIGNED");

        // 取消其中 2 笔
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM service_order WHERE slot_id = ? ORDER BY id LIMIT 2", Long.class, slotId);
        for (Long id : ids) {
            mockMvc.perform(post("/api/service-orders/{id}/cancel", id)
                            .header("scc-test-token", "Bearer " + elderToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reason":"分页筛选准备","version":1}"""))
                    .andExpect(status().isOk());
        }

        // 按状态筛，total 与记录数都应变
        JsonNode cancelled = getPaged(
                "/api/service-orders/mine/paged?pageNum=1&pageSize=20&status=CANCELLED", elderToken);
        assertThat(cancelled.path("total").asLong()).as("已取消应为 2 笔").isEqualTo(2);
        assertThat(cancelled.path("records").size()).isEqualTo(2);
        for (JsonNode order : cancelled.path("records")) {
            assertThat(order.path("status").asText())
                    .as("筛选结果里不应混入其它状态").isEqualTo("CANCELLED");
        }

        // 未分派的应是 0 笔——prepare 已把 7 笔全部分派出去
        JsonNode pending = getPaged(
                "/api/service-orders/mine/paged?pageNum=1&pageSize=20&status=PENDING_ASSIGNMENT", elderToken);
        assertThat(pending.path("total").asLong())
                .as("prepare 已全部分派，待分派应为 0 笔").isZero();

        // 未取消的应为剩下的 5 笔
        JsonNode notCancelled = getPaged(
                "/api/service-orders/mine/paged?pageNum=1&pageSize=20&status=ASSIGNED", elderToken);
        assertThat(notCancelled.path("total").asLong())
                .as("未取消的应为 " + (OWN_ORDERS - 2) + " 笔").isEqualTo(OWN_ORDERS - 2);

        // 两个筛选的 total 之和应等于全量
        assertThat(cancelled.path("total").asLong() + notCancelled.path("total").asLong())
                .as("分状态计数之和应等于全量").isEqualTo(OWN_ORDERS);
    }

    // ==================== 工作人员本人任务 ====================

    @Test
    @DisplayName("工作人员本人任务：服务端分页，total 等于分派给本人的订单数")
    void staffTaskListIsServerSidePaged() throws Exception {
        JsonNode page = getPaged("/api/service-orders/my-tasks/paged?pageNum=1&pageSize=4", staffToken);

        assertThat(page.path("records").size()).as("第1页应只返回 4 条").isEqualTo(4);
        assertThat(page.path("total").asLong())
                .as("total 应等于分派给本人的订单数").isEqualTo(OWN_ORDERS);
    }

    @Test
    @DisplayName("工作人员本人任务：翻页换不掉归属，且角色门禁仍在")
    void staffTaskPagingIsBoundedBySession() throws Exception {
        Set<Long> seen = new HashSet<>();
        for (int pageNum = 1; pageNum <= 3; pageNum++) {
            JsonNode page = getPaged(
                    "/api/service-orders/my-tasks/paged?pageNum=" + pageNum + "&pageSize=3", staffToken);
            for (JsonNode order : page.path("records")) {
                assertThat(seen.add(order.path("id").asLong()))
                        .as("任务在第 " + pageNum + " 页重复出现").isTrue();
            }
        }
        assertThat(seen).hasSize(OWN_ORDERS);

        // 角色门禁：老人不能查任务分页入口
        mockMvc.perform(get("/api/service-orders/my-tasks/paged")
                        .header("scc-test-token", "Bearer " + elderToken))
                .andExpect(status().isForbidden());

        // 未登录也不行
        mockMvc.perform(get("/api/service-orders/my-tasks/paged"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("工作人员本人任务：状态筛选在服务端生效")
    void staffTaskListFiltersByStatus() throws Exception {
        JsonNode assigned = getPaged(
                "/api/service-orders/my-tasks/paged?pageNum=1&pageSize=20&status=ASSIGNED", staffToken);
        assertThat(assigned.path("total").asLong())
                .as("刚分派完应全是 ASSIGNED").isEqualTo(OWN_ORDERS);
        for (JsonNode order : assigned.path("records")) {
            assertThat(order.path("status").asText()).isEqualTo("ASSIGNED");
        }

        // 筛一个不存在的状态，应为 0 而不是「返回全部」
        JsonNode none = getPaged(
                "/api/service-orders/my-tasks/paged?pageNum=1&pageSize=20&status=TERMINATED", staffToken);
        assertThat(none.path("total").asLong()).as("不存在的状态应为 0").isZero();
        assertThat(none.path("records").size()).isZero();
    }

    // ==================== 分页边界 ====================

    @Test
    @DisplayName("分页边界：页码超范围返回空记录但 total 正确，不报错")
    void pagingOutOfRangeReturnsEmptyWithCorrectTotal() throws Exception {
        JsonNode beyond = getPaged(
                "/api/service-orders/mine/paged?pageNum=99&pageSize=10", elderToken);
        assertThat(beyond.path("records").size()).as("超范围页应返回空").isZero();
        assertThat(beyond.path("total").asLong())
                .as("total 仍应反映真实总数").isEqualTo(OWN_ORDERS);
    }

    @Test
    @DisplayName("分页边界：非法 pageSize 被收敛到允许范围，不接受任意大小")
    void illegalPageSizeIsClamped() throws Exception {
        // 超上限：应被收敛到 100，而不是照单全收
        JsonNode huge = getPaged(
                "/api/service-orders/mine/paged?pageNum=1&pageSize=99999", elderToken);
        assertThat(huge.path("pageSize").asInt())
                .as("超上限的 pageSize 应被收敛").isLessThanOrEqualTo(100);

        // 0 或负数：应回落到默认，而不是报错或返回全部
        JsonNode zero = getPaged(
                "/api/service-orders/mine/paged?pageNum=1&pageSize=0", elderToken);
        assertThat(zero.path("pageSize").asInt())
                .as("0 应回落到默认页大小").isEqualTo(20);

        JsonNode negative = getPaged(
                "/api/service-orders/mine/paged?pageNum=-1&pageSize=10", elderToken);
        assertThat(negative.path("pageNum").asInt())
                .as("负页码应回落到第 1 页").isEqualTo(1);
    }

    @Test
    @DisplayName("排序稳定：连续两次请求同一页，返回的 ID 顺序完全一致")
    void orderingIsStableAcrossRequests() throws Exception {
        List<Long> first = idsOf(getPaged(
                "/api/service-orders/mine/paged?pageNum=2&pageSize=3", elderToken));
        List<Long> second = idsOf(getPaged(
                "/api/service-orders/mine/paged?pageNum=2&pageSize=3", elderToken));

        assertThat(second)
                .as("同一页两次请求的顺序应完全一致（ORDER BY 需有 id 兜底）")
                .isEqualTo(first);
        assertThat(first).as("第2页应有记录").isNotEmpty();
    }

    // ==================== 辅助 ====================

    /** 请求分页端点并返回 data 节点 */
    private JsonNode getPaged(String url, String token) throws Exception {
        String body = mockMvc.perform(get(url).header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = JSON.readTree(body).path("data");
        if (!data.isObject() || !data.has("records")) {
            throw new IllegalStateException("分页响应结构不对，data 应为含 records 的对象：" + body);
        }
        return data;
    }

    private List<Long> idsOf(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode order : page.path("records")) {
            ids.add(order.path("id").asLong());
        }
        return ids;
    }

    private Long createSlot(int capacity) {
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
        if (slot.getId() == null) {
            throw new IllegalStateException("时段插入后未回填主键");
        }
        return slot.getId();
    }

    private String bookAs(String token) throws Exception {
        String resp = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"分页测试",
                                 "contactPhone":"13900000008",
                                 "address":"分页测试地址","requestId":"%s"}
                                """.formatted(slotId, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
        if (!m.find()) {
            throw new IllegalStateException("建单响应中未找到订单 ID：" + resp);
        }
        return m.group(1);
    }

    /** 管理员令牌：分派准备需要 */
    private String adminTokenCache;

    private String adminToken() throws Exception {
        if (adminTokenCache == null) {
            createAccount("it_page_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
            adminTokenCache = loginAndGetToken(mockMvc, "it_page_admin", ADMIN_PASSWORD);
        }
        return adminTokenCache;
    }
}
