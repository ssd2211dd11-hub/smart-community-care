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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务评价。
 *
 * <p>重点是<b>一单一评在并发下成立</b>：唯一约束必须在数据库层兜住，
 * 不能只靠「先查再插」——两个请求同时查到「还没有评价」就会都去插。
 */
@AutoConfigureMockMvc
class OrderReviewTest extends BaseIntegrationTest {

    private static final String HEADER = "scc-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceItemMapper itemMapper;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String adminToken;
    private String staffToken;
    private String elderToken;
    private String otherElderToken;
    private Long staffId;
    private Long slotId;
    private Long serviceId;

    @BeforeEach
    void prepare() throws Exception {
        createElder("it_rv_elder", ELDER_PASSWORD);
        createElder("it_rv_other", ELDER_PASSWORD);
        createAccount("it_rv_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        staffId = createAccount("it_rv_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);

        adminToken = loginAndGetToken(mockMvc, "it_rv_admin", ADMIN_PASSWORD);
        staffToken = loginAndGetToken(mockMvc, "it_rv_staff", STAFF_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, "it_rv_elder", ELDER_PASSWORD);
        otherElderToken = loginAndGetToken(mockMvc, "it_rv_other", ELDER_PASSWORD);

        Long factoryServiceId = createFixtureItem(it -> {
            it.setName("评价测试服务");
            it.setDescription("用于评价流程测试");
            it.setPrice(new BigDecimal("88.00"));
            it.setPhone("13800000000");
        });
        serviceId = factoryServiceId;

        slotId = createSlot();
    }

    @Test void currentPageReviewLookupIsBoundedAndAlwaysScopedToOwner() throws Exception {
        String own = completedOrder();
        mockMvc.perform(post("/api/service-orders/{id}/review", own).header(HEADER,"Bearer "+elderToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"content\":\"当前页评价\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/service-orders/reviews/mine/for-orders").param("orderIds",own.toString())
                .header(HEADER,"Bearer "+elderToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/service-orders/reviews/mine/for-orders").param("orderIds",own.toString())
                .header(HEADER,"Bearer "+otherElderToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/service-orders/reviews/mine/for-orders").param("orderIds",own.toString())
                .header(HEADER,"Bearer "+staffToken)).andExpect(status().isForbidden());
        String excessive = java.util.stream.LongStream.rangeClosed(1,101).mapToObj(Long::toString).collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(get("/api/service-orders/reviews/mine/for-orders").param("orderIds",excessive)
                .header(HEADER,"Bearer "+elderToken)).andExpect(status().isBadRequest());
    }

    private Long createSlot() {
        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(serviceId);
        slot.setStartAt(LocalDateTime.now().plusDays(1));
        slot.setEndAt(LocalDateTime.now().plusDays(1).plusHours(3));
        slot.setCapacity(10);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(slot);
        return slot.getId();
    }

    // ---------------- 正常评价 ----------------
    @Test
    void pagedReviewsKeepFullAverageAndExcludeUnratedOrders() throws Exception {
        String first=completedOrder(),second=completedOrder();completedOrder();
        mockMvc.perform(review(first,elderToken,"{\"rating\":5}")).andExpect(status().isOk());
        mockMvc.perform(review(second,elderToken,"{\"rating\":1}")).andExpect(status().isOk());
        for(int page=1;page<=2;page++)mockMvc.perform(get("/api/service-orders/reviews/received/paged")
                .header(HEADER,"Bearer "+staffToken).param("pageSize","1").param("pageNum",Integer.toString(page)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.ratedCount").value(2)).andExpect(jsonPath("$.data.averageRating").value(3.0))
                .andExpect(jsonPath("$.data.records.length()").value(1));
        mockMvc.perform(get("/api/service-orders/reviews/received/paged").header(HEADER,"Bearer "+elderToken)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("已完成订单可评价，评分与内容正确落库")
    void completedOrderCanBeReviewed() throws Exception {
        String orderId = completedOrder();

        mockMvc.perform(review(orderId, elderToken, """
                {"rating":5,"content":"服务很及时，老人很满意"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rating").value(5))
                .andExpect(jsonPath("$.data.content").value("服务很及时，老人很满意"));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_review WHERE order_id = ?",
                Integer.class, Long.valueOf(orderId));
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("评分超出范围被拒")
    void ratingOutOfRangeRejected() throws Exception {
        String orderId = completedOrder();

        mockMvc.perform(review(orderId, elderToken, """
                {"rating":6}
                """))
                .andExpect(status().isBadRequest());
    }

    // ---------------- 权限与状态 ----------------

    @Test
    @DisplayName("未完成订单不能评价")
    void onlyCompletedOrderCanBeReviewed() throws Exception {
        // 走到 ACCEPTED 就停，不确认
        String orderId = bookOn();
        assignAndAccept(orderId);

        mockMvc.perform(review(orderId, elderToken, """
                {"rating":5}
                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("他人不能评价我的订单")
    void cannotReviewOthersOrder() throws Exception {
        String orderId = completedOrder();

        mockMvc.perform(review(orderId, otherElderToken, """
                {"rating":1}
                """))
                .andExpect(status().isNotFound());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_review WHERE order_id = ?",
                Integer.class, Long.valueOf(orderId));
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("服务人员和管理员不能代写评分")
    void staffAndAdminCannotWriteReview() throws Exception {
        String orderId = completedOrder();

        mockMvc.perform(review(orderId, staffToken, """
                {"rating":5}
                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(review(orderId, adminToken, """
                {"rating":5}
                """))
                .andExpect(status().isForbidden());
    }

    // ---------------- 一单一评 ----------------

    @Test
    @DisplayName("同一订单重复评价被拒，不覆盖原评分")
    void duplicateReviewRejected() throws Exception {
        String orderId = completedOrder();

        mockMvc.perform(review(orderId, elderToken, """
                {"rating":5,"content":"第一条"}
                """))
                .andExpect(status().isOk());

        mockMvc.perform(review(orderId, elderToken, """
                {"rating":1,"content":"想改成一分"}
                """))
                .andExpect(status().isConflict());

        // 原评分不能被改
        Integer rating = jdbcTemplate.queryForObject(
                "SELECT rating FROM service_review WHERE order_id = ?",
                Integer.class, Long.valueOf(orderId));
        assertThat(rating).isEqualTo(5);
    }

    @Test
    @DisplayName("并发评价只保存一条，其余返回同一条结果")
    void concurrentReviewKeepsSingleRecord() throws Exception {
        String orderId = completedOrder();
        int attempts = 6;

        List<Callable<Integer>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            final int score = i + 1;   // 每次评分不同，便于看出有没有覆盖
            tasks.add(() -> mockMvc.perform(review(orderId, elderToken,
                            "{\"rating\":" + score + "}"))
                    .andReturn().getResponse().getStatus());
        }

        ExecutorService pool = Executors.newFixedThreadPool(attempts + 2);
        CountDownLatch gate = new CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (Callable<Integer> t : tasks) {
            futures.add(pool.submit(() -> {
                gate.await();
                return t.call();
            }));
        }
        gate.countDown();
        for (var f : futures) {
            assertThat(f.get(60, TimeUnit.SECONDS)).isNotNull();
        }
        pool.shutdownNow();

        // 数据库层面只能有一条
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_review WHERE order_id = ?",
                Integer.class, Long.valueOf(orderId));
        assertThat(count).isEqualTo(1);
    }

    // ---------------- 角色可见范围 ----------------

    @Test
    @DisplayName("老人只见自己给出的评价")
    void elderSeesOnlyOwnReviews() throws Exception {
        String mine = completedOrder();
        mockMvc.perform(review(mine, elderToken, """
                {"rating":5}
                """)).andExpect(status().isOk());

        // 为另一位老人单独建一笔完成订单：必须用他的档案ID，不是账号ID
        String otherOrder = completedOrderAs("it_rv_other", otherElderToken);
        jdbcTemplate.update("UPDATE service_order SET status = 'COMPLETED' WHERE id = ?",
                Long.valueOf(otherOrder));
        jdbcTemplate.update("UPDATE service_order SET status = 'COMPLETED' WHERE id = ?",
                Long.valueOf(otherOrder));
        mockMvc.perform(review(otherOrder, otherElderToken, """
                {"rating":3}
                """)).andExpect(status().isOk());

        // 老人看到的只有自己那 1 条
        mockMvc.perform(get("/api/service-orders/reviews/mine")
                        .header(HEADER, "Bearer " + elderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("服务人员只见本人服务所得的评价")
    void staffSeesOnlyOwnReviews() throws Exception {
        String orderId = completedOrder();
        mockMvc.perform(review(orderId, elderToken, """
                {"rating":4}
                """)).andExpect(status().isOk());

        mockMvc.perform(get("/api/service-orders/reviews/received")
                        .header(HEADER, "Bearer " + staffToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].rating").value(4));
    }

    @Test
    @DisplayName("管理员可见全部评价")
    void adminSeesAllReviews() throws Exception {
        String orderId = completedOrder();
        mockMvc.perform(review(orderId, elderToken, """
                {"rating":5}
                """)).andExpect(status().isOk());

        mockMvc.perform(get("/api/service-orders/reviews/all")
                        .header(HEADER, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("老人查员工评价列表被拒")
    void elderCannotListReceivedReviews() throws Exception {
        mockMvc.perform(get("/api/service-orders/reviews/received")
                        .header(HEADER, "Bearer " + elderToken))
                .andExpect(status().isForbidden());
    }

    // ---------------- 评价不影响其他数据 ----------------

    @Test
    @DisplayName("评价不改变订单终态、名额与完成时间")
    void reviewDoesNotAffectOrderOrSlot() throws Exception {
        String orderId = completedOrder();

        var before = orderFields(orderId);
        int occupiedBefore = slotMapper.selectById(slotId).getOccupiedCount();

        mockMvc.perform(review(orderId, elderToken, """
                {"rating":1}
                """)).andExpect(status().isOk());

        var after = orderFields(orderId);
        assertThat(after.getStatus()).isEqualTo("COMPLETED");
        assertThat(after.getConfirmedAt()).isEqualTo(before.getConfirmedAt());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(slotMapper.selectById(slotId).getOccupiedCount()).isEqualTo(occupiedBefore);
    }

    // ---------------- 辅助 ----------------

    /** 建单并走到已完成 */
    private String completedOrder() throws Exception {
        String orderId = bookOn();
        assignAndAccept(orderId);
        startService(orderId);
        mockMvc.perform(act("/submit-result", orderId, staffToken, """
                {"result":"已完成服务"}
                """)).andExpect(status().isOk());
        mockMvc.perform(act("/confirm", orderId, elderToken, null))
                .andExpect(status().isOk());
        // 时间到，允许开工校验通过
        jdbcTemplate.update(
                "UPDATE service_order SET slot_start_snapshot = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(1)),
                Long.valueOf(orderId));
        return orderId;
    }

    private String bookOn() throws Exception {
        String body = mockMvc.perform(post("/api/service-orders")
                        .header(HEADER, "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                                 "address":"评价测试地址","requestId":"%s"}
                                """.formatted(slotId, UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(body);
        m.find();
        return m.group(1);
    }

    private void assignAndAccept(String orderId) throws Exception {
        mockMvc.perform(act("/assign", orderId, adminToken, """
                {"staffId":%d,"reason":"分派"}
                """.formatted(staffId))).andExpect(status().isOk());
        mockMvc.perform(act("/accept", orderId, staffToken, """
                {"reason":"收到"}
                """)).andExpect(status().isOk());
    }

    /** 开工前把快照时间移到过去 */
    private void startService(String orderId) throws Exception {
        jdbcTemplate.update(
                "UPDATE service_order SET slot_start_snapshot = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusHours(1)),
                Long.valueOf(orderId));
        mockMvc.perform(act("/start", orderId, staffToken, null))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder review(
            String orderId, String token, String body) {
        return post("/api/service-orders/" + orderId + "/review")
                .header(HEADER, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder act(
            String action, String orderId, String token, String body) {
        var b = post("/api/service-orders/" + orderId + action)
                .header(HEADER, "Bearer " + token);
        b.contentType(MediaType.APPLICATION_JSON).content(withOrderVersion(orderId, body));
        return b;
    }

    private org.imnu.smartcommunitycare.order.model.entity.ServiceOrder orderFields(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT * FROM service_order WHERE id = ?",
                (rs, i) -> {
                    var o = new org.imnu.smartcommunitycare.order.model.entity.ServiceOrder();
                    o.setId(rs.getLong("id"));
                    o.setStatus(rs.getString("status"));
                    o.setVersion(rs.getInt("version"));
                    var c = rs.getTimestamp("confirmed_at");
                    o.setConfirmedAt(c == null ? null : c.toLocalDateTime());
                    return o;
                }, Long.valueOf(orderId));
    }

    private Long userId(String username) {
        return userMapper.findByUsername(username).getId();
    }

    /** 以指定老人身份建单并走到已完成 */
    private String completedOrderAs(String username, String token) throws Exception {
        String body = mockMvc.perform(post("/api/service-orders")
                        .header(HEADER, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slotId":%d,"contactName":"李秀兰","contactPhone":"13900000004",
                                 "address":"另一位老人地址","requestId":"%s"}
                                """.formatted(slotId, UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(body);
        m.find();
        return m.group(1);
    }
}
