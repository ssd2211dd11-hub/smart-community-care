package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceItemMapper;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预约名额与防重的集成测试。
 *
 * <p>全部走真实 HTTP 接口（MockMvc），与浏览器实际调用路径一致，
 * 这样接口契约与数据不变量能一起被验证。
 *
 * <p>断言重点是<b>数据不变量</b>而非返回码：名额不超发、重复请求不二次占用、
 * 取消只释放一次。接口返回成功但名额被多扣一次，同样是缺陷。
 */
@AutoConfigureMockMvc
class OrderBookingTest extends BaseIntegrationTest {

    private static final String ELDER = "it_order_elder";
    private static final String TOKEN_HEADER = "scc-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceItemMapper itemMapper;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String elderToken;
    private Long slotId;

    @BeforeEach
    void prepareSlot() throws Exception {
        createElder(ELDER, ELDER_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);

        Long factoryServiceId = createFixtureItem(it -> {
            it.setName("测试服务");
            it.setDescription("用于订单测试");
            it.setPrice(new BigDecimal("50.00"));
            it.setPhone("13800000000");
        });

        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(factoryServiceId);
        slot.setStartAt(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        slot.setEndAt(LocalDateTime.now().plusDays(1).withHour(12).withMinute(0));
        slot.setCapacity(2);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(slot);

        slotId = slot.getId();
    }

    // ---------------- 用例 ----------------

    @Test
    @DisplayName("创建预约后名额加一，出现一条待分派订单")
    void createOccupiesOneSlot() throws Exception {
        createOrder(newRequestId());

        assertThat(currentOccupied()).isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("容量为 2 时最多建 2 单，第三单被拒且名额不超发")
    void thirdOrderRejectedWhenFull() throws Exception {
        createOrder(newRequestId());
        createOrder(newRequestId());
        assertThat(currentOccupied()).isEqualTo(2);

        mockMvc.perform(asElder(post("/api/service-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(newRequestId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("名额已满")));

        // 关键断言：失败后名额不能变成 3，也不能留下半笔订单
        assertThat(currentOccupied()).isEqualTo(2);
        assertThat(orderCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("同一 requestId 重试返回原订单，不重复占用名额")
    void sameRequestIdIsIdempotent() throws Exception {
        String requestId = newRequestId();

        String firstId = createOrderAndGetId(requestId);
        String secondId = createOrderAndGetId(requestId);

        assertThat(secondId).isEqualTo(firstId);
        assertThat(currentOccupied()).isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 requestId 但内容不同返回冲突")
    void sameRequestIdDifferentContentRejected() throws Exception {
        String requestId = newRequestId();
        createOrderAndGetId(requestId);

        mockMvc.perform(asElder(post("/api/service-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"李秀兰","contactPhone":"13900000004",
                                 "address":"幸福社区2号楼202室","requestId":"%s"}
                                """.formatted(slotId, requestId)))
                .andExpect(status().isConflict());

        assertThat(currentOccupied()).isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("取消订单释放一个名额")
    void cancelReleasesOneSlot() throws Exception {
        String orderId = createOrderAndGetId(newRequestId());
        assertThat(currentOccupied()).isEqualTo(1);

        mockMvc.perform(asElder(post("/api/service-orders/" + orderId + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withOrderVersion(orderId, """
                                {"reason":"临时有事"}
                                """)))
                .andExpect(status().isOk());

        assertThat(currentOccupied()).isEqualTo(0);
    }

    @Test
    @DisplayName("重复取消不会二次释放名额")
    void repeatedCancelDoesNotDoubleRelease() throws Exception {
        String orderId = createOrderAndGetId(newRequestId());

        mockMvc.perform(asElder(post("/api/service-orders/" + orderId + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withOrderVersion(orderId, """
                                {"reason":"第一次取消"}
                                """)))
                .andExpect(status().isOk());
        assertThat(currentOccupied()).isEqualTo(0);

        // 第二次必须失败，名额不能再减
        mockMvc.perform(asElder(post("/api/service-orders/" + orderId + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withOrderVersion(orderId, """
                                {"reason":"再次取消"}
                                """)))
                .andExpect(status().isConflict());

        assertThat(currentOccupied()).isEqualTo(0);
    }

    @Test
    @DisplayName("已关闭的时段不能预约")
    void closedSlotRejectsOrder() throws Exception {
        ServiceSlot slot = slotMapper.selectById(slotId);
        slot.setEnabled(false);
        slotMapper.updateById(slot);

        mockMvc.perform(asElder(post("/api/service-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(newRequestId())))
                .andExpect(status().isBadRequest());

        assertThat(currentOccupied()).isEqualTo(0);
    }

    @Test
    @DisplayName("非老人角色不能创建预约")
    void nonElderCannotCreateOrder() throws Exception {
        createAccount("it_order_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_order_admin", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/service-orders")
                        .header(TOKEN_HEADER, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(newRequestId())))
                .andExpect(status().isForbidden());

        assertThat(currentOccupied()).isEqualTo(0);
    }

    @Test
    @DisplayName("未登录不能创建预约")
    void anonymousCannotCreateOrder() throws Exception {
        mockMvc.perform(post("/api/service-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(newRequestId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("他人订单详情返回 404，不泄露订单是否存在")
    void otherElderCannotSeeOrder() throws Exception {
        String orderId = createOrderAndGetId(newRequestId());

        createElder("it_order_other", ELDER_PASSWORD);
        String otherToken = loginAndGetToken(mockMvc, "it_order_other", ELDER_PASSWORD);

        mockMvc.perform(get("/api/service-orders/" + orderId)
                        .header(TOKEN_HEADER, "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    // ---------------- 辅助 ----------------

    private MockHttpServletRequestBuilder asElder(MockHttpServletRequestBuilder builder) {
        return builder.header(TOKEN_HEADER, "Bearer " + elderToken);
    }

    private void createOrder(String requestId) throws Exception {
        mockMvc.perform(asElder(post("/api/service-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(requestId)))
                .andExpect(status().isOk());
    }

    /** 创建订单并取出订单 ID（用正则而非 JSON 库，避免绑定序列化实现） */
    private String createOrderAndGetId(String requestId) throws Exception {
        String body = mockMvc.perform(asElder(post("/api/service-orders"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(requestId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("响应中未找到订单 ID：" + body);
        }
        return matcher.group(1);
    }

    private String createBody(String requestId) {
        return """
                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                 "address":"幸福社区1号楼101室","requestId":"%s"}
                """.formatted(slotId, requestId);
    }

    private int currentOccupied() {
        return slotMapper.selectById(slotId).getOccupiedCount();
    }

    private int orderCount() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_order WHERE slot_id = ?", Integer.class, slotId);
        return n == null ? 0 : n;
    }

    private String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
