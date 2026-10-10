package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceItemMapper;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.util.JsonPathExpectationsHelper;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端订单视图与人员选择列表的测试。
 *
 * <p>验证重点是<b>后端授权</b>：老人访问管理员订单列表必须被拒。
 * 页面路由守卫只是体验，真正的边界在后端。
 */
@AutoConfigureMockMvc
class AdminOrderViewTest extends BaseIntegrationTest {

    private static final String HEADER = "scc-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceItemMapper itemMapper;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String adminToken;
    private String elderToken;
    private Long slotId;

    @BeforeEach
    void prepare() throws Exception {
        createElder("it_view_elder", ELDER_PASSWORD);
        createAccount("it_view_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        createAccount("it_view_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_view_staff_off", STAFF_PASSWORD, UserRole.SERVICE_STAFF, false);

        adminToken = loginAndGetToken(mockMvc, "it_view_admin", ADMIN_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, "it_view_elder", ELDER_PASSWORD);

        Long factoryServiceId = createFixtureItem(it -> {
            it.setName("视图测试服务");
            it.setDescription("用于管理端视图测试");
            it.setPrice(new BigDecimal("70.00"));
            it.setPhone("13800000000");
        });

        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(factoryServiceId);
        slot.setStartAt(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        slot.setEndAt(LocalDateTime.now().plusDays(1).withHour(12).withMinute(0));
        slot.setCapacity(5);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(slot);
        slotId = slot.getId();
    }

    @Test
    @DisplayName("管理员可查询全部订单，返回分页结构")
    void adminCanListAllOrders() throws Exception {
        bookOne();

        mockMvc.perform(get("/api/service-orders/admin/all")
                        .header(HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.records[0].orderNo").isNotEmpty());
    }

    @Test
    @DisplayName("老人访问管理员订单列表返回 403，不是靠前端菜单隐藏")
    void elderCannotListAllOrders() throws Exception {
        mockMvc.perform(get("/api/service-orders/admin/all")
                        .header(HEADER, "Bearer " + elderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("未登录访问管理员订单列表返回 401")
    void anonymousCannotListAllOrders() throws Exception {
        mockMvc.perform(get("/api/service-orders/admin/all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("按状态筛选只返回该状态的订单")
    void filterByStatusWorks() throws Exception {
        bookOne();

        // 全部（不过滤）
        mockMvc.perform(get("/api/service-orders/admin/all")
                        .header(HEADER, "Bearer " + adminToken))
                .andExpect(jsonPath("$.data.total").value(1));

        // 匹配状态
        mockMvc.perform(get("/api/service-orders/admin/all")
                        .header(HEADER, "Bearer " + adminToken)
                        .param("status", "PENDING_ASSIGNMENT"))
                .andExpect(jsonPath("$.data.total").value(1));

        // 不匹配状态
        mockMvc.perform(get("/api/service-orders/admin/all")
                        .header(HEADER, "Bearer " + adminToken)
                        .param("status", "COMPLETED"))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    @Test
    @DisplayName("分页生效：pageSize=1 时总数仍为全部数量")
    void paginationIsServerSide() throws Exception {
        bookOne();
        bookOne();

        mockMvc.perform(get("/api/service-orders/admin/all")
                        .header(HEADER, "Bearer " + adminToken)
                        .param("pageNum", "1")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.records.length()").value(1));
    }

    @Test
    @DisplayName("可分派人员只返回启用服务人员，不含停用人员与密码哈希")
    void assignableStaffOnlyEnabledAndMinimal() throws Exception {
        mockMvc.perform(get("/api/users/assignable-staff")
                        .header(HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                // 按名字定位而不是按条数：清理只删夹具，库里可能有意保留的非夹具员工，
                // 用 $.data[0] 会取到别人的对象，密码字段检查就形同虚设。
                .andExpect(jsonPath("$.data[?(@.realName=='测试-it_view_staff')]").isNotEmpty())
                // 停用的 it_view_staff_off 不应出现
                .andExpect(jsonPath("$.data[?(@.realName=='测试-it_view_staff_off')]").isEmpty())
                // 直接断言筛选对象没有敏感字段，避免数组索引使缺失路径断言误通过。
                .andExpect(jsonPath("$.data[?(@.realName=='测试-it_view_staff')].passwordHash")
                        .doesNotExist())
                .andExpect(jsonPath("$.data[?(@.realName=='测试-it_view_staff')].phone")
                        .doesNotExist());
    }

    @Test
    void emptyAndExtremeAdminPagesKeepTheFilteredTotal() throws Exception {
        bookOne();
        for (String page : new String[]{"3", Integer.toString(Integer.MAX_VALUE)}) {
            mockMvc.perform(get("/api/service-orders/admin/all")
                            .header(HEADER, "Bearer " + adminToken)
                            .param("pageNum", page).param("pageSize", "100")
                            .param("status", "PENDING_ASSIGNMENT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.records").isEmpty());
        }
    }

    /**
     * 反例验证：证明上面那条敏感字段断言<b>确实能检出泄露</b>。
     *
     * <p>断言「不存在」有个众所周知的弱点：路径写错、类型不匹配时，
     * 它同样会通过。本用例拿一份<b>确定含敏感字段</b>的响应喂给同一条断言，
     * 要求它<b>必须失败</b>——不失败就说明上面的断言是空转。
     *
     * <p>正常响应会通过：{@link #assignableStaffOnlyEnabledAndMinimal} 已覆盖。
     * 本用例只负责反例这一侧，两者合起来才是完整证据。
     */
    @Test
    @DisplayName("反例：响应含敏感字段时，字段缺失断言必须失败")
    void assignableStaffResponseRejectsSensitiveFieldProbe() {
        // 结构与真实响应一致：data 是数组，元素含 realName + passwordHash + phone
        String leaked = """
                {"data":[{"realName":"测试-it_view_staff",\
                "passwordHash":"leak-marker","phone":"leak-marker"}]}""";

        String path = "$.data[?(@.realName=='测试-it_view_staff')].passwordHash";

        // 前置确认：这份「泄露」响应确实带了 passwordHash。
        // 不用 JsonPath.read 做这个检查——该路径返回的是过滤结果数组，
        // 不能把数组结果赋给 String；这与错误路径读取到空数组是两件事。
        // 直接看原始 JSON 是否含该字段即可。
        Assertions.assertTrue(leaked.contains("\"passwordHash\""),
                "前置：构造的响应应当确实包含 passwordHash 字段");

        // 核心断言：同样的断言，对含敏感字段的响应必须失败
        Assertions.assertThrows(AssertionError.class,
                () -> new JsonPathExpectationsHelper(path).doesNotExist(leaked),
                "该断言对含 passwordHash 的响应仍然通过——它是空转，检不出泄露");
    }

    @Test
    @DisplayName("老人不能查询可分派人员列表")
    void elderCannotListAssignableStaff() throws Exception {
        mockMvc.perform(get("/api/users/assignable-staff")
                        .header(HEADER, "Bearer " + elderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("工作人员查看未分派订单详情返回 404，不能用 500 区分其是否存在")
    void staffCannotReadUnassignedOrderDetail() throws Exception {
        assertUnassignedOrderHidden("");
    }

    @Test
    @DisplayName("工作人员查看未分派订单日志返回 404，与订单详情权限一致")
    void staffCannotReadUnassignedOrderLogs() throws Exception {
        assertUnassignedOrderHidden("/logs");
    }

    private void assertUnassignedOrderHidden(String suffix) throws Exception {
        bookOne();
        Long orderId = jdbcTemplate.queryForObject(
                "SELECT id FROM service_order WHERE slot_id = ?", Long.class, slotId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT staff_id FROM service_order WHERE id = ?", Long.class, orderId))
                .isNull();
        String staffToken = loginAndGetToken(mockMvc, "it_view_staff", STAFF_PASSWORD);

        mockMvc.perform(get("/api/service-orders/" + orderId + suffix)
                        .header(HEADER, "Bearer " + staffToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("订单不存在"))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get("/api/service-orders/" + Long.MAX_VALUE + suffix)
                        .header(HEADER, "Bearer " + staffToken))
                .andExpect(status().isNotFound());
    }

    // ---------------- 辅助 ----------------

    private void bookOne() throws Exception {
        mockMvc.perform(post("/api/service-orders")
                        .header(HEADER, "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                                 "address":"视图测试地址","requestId":"%s"}
                                """.formatted(slotId, UUID.randomUUID().toString())))
                .andExpect(status().isOk());
    }
}
