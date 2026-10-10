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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 建单与下架竞争下的并发不变量压力测试。
 * 任务结束时服务已下架且存在订单，不足以判断受理与下架的先后；确定次序另由同步闸测试验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderDisableWindowTest extends BaseIntegrationTest {

    private static final String ELDER = "it_win_elder";
    private static final String ADMIN = "it_win_admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String elderToken;
    private String adminToken;
    private Long serviceId;
    private Long slotId;

    @BeforeEach
    void prepare() throws Exception {
        createElder(ELDER, ELDER_PASSWORD);
        createAccount(ADMIN, ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);
        adminToken = loginAndGetToken(mockMvc, ADMIN, ADMIN_PASSWORD);

        serviceId = createFixtureItem(it -> {
            it.setName("窗口测试服务");
            it.setDescription("用于验证下架与建单的竞争窗口");
            it.setPrice(new BigDecimal("50.00"));
            it.setPhone("13900000000");
        });
        slotId = createSlot(20);
    }

    /**
     * 串行对照：下架先提交、之后建单，必须被拒绝。
     *
     * <p>这是窗口的<b>下界</b>——只要下架已落库，建单就该被拒。
     * 它现在是通过的，说明服务端逻辑本身没错；问题只在并发窗口。
     */
    @Test
    @DisplayName("窗口下界：下架提交后建单被拒")
    void bookingAfterDisableCommittedRejected() throws Exception {
        assertEquals(200, disableService());

        assertEquals(400, bookStatus(),
                "服务已下架且已提交，新预约应被拒绝");
        assertEquals(0, orderCount(), "被拒的建单不应留下订单");
    }

    /**
     * 并发：下架与建单同时起跑，反复多轮，断言<b>不变量</b>。
     *
     * <p><b>刻意不断言「绝不下架后建单」</b>：
     * 「先受理后下架」是完全合法的行为，在并发下无法从结果区分
     * 「先受理」与「下架后才建单」——这正是原统计失效的原因。
     * 有确定先后的判定见 {@link OrderLockOrderCompetitionTest}。
     *
     * <p>本用例的定位是<b>压力回归</b>：无论交错如何，
     * 名额、订单数、必要 CREATE 日志三者必须相符，且不超容量、不出负名额。
     */
    @Test
    @DisplayName("并发下架与建单：多轮下不变量不被破坏（无超容量、无负名额、计数一致）")
    void concurrentDisableNeverCorruptsCounts() throws Exception {
        int rounds = 5;

        for (int round = 0; round < rounds; round++) {
            // 每轮恢复服务启用与时段占用，隔离各轮之间
            db.update("UPDATE service_item SET enabled = 1 WHERE id = ?", serviceId);
            db.update("UPDATE service_slot SET enabled = 1, occupied_count = 0 WHERE id = ?", slotId);
            db.update("DELETE FROM service_order_log WHERE order_id IN"
                    + " (SELECT id FROM service_order WHERE slot_id = ?)", slotId);
            db.update("DELETE FROM service_order WHERE slot_id = ?", slotId);

            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String rid = UUID.randomUUID().toString();
                tasks.add(() -> bookStatusWithId(rid));
            }
            tasks.add(() -> disableService());

            List<Integer> results = runConcurrently(tasks);
            int disableStatus = results.get(results.size() - 1);
            List<Integer> bookResults = results.subList(0, results.size() - 1);

            if (disableStatus >= 500) {
                throw new IllegalStateException(
                        "下架请求返回 " + disableStatus + "：服务端错误（并发下可能是数据库死锁），"
                                + "请查后端日志是否有 DeadlockLoserDataAccessException");
            }

            long ok = bookResults.stream().filter(r -> r == 200).count();
            long rejected = bookResults.stream().filter(r -> r == 400 || r == 409).count();
            assertEquals(4, ok + rejected,
                    "4 笔建单都应有明确结局，实际：" + bookResults);

            // 不变量：无负名额、不超容量
            int occupied = occupiedOf();
            assertTrue(occupied >= 0, "第 " + round + " 轮：占用不得为负，实际 " + occupied);
            assertTrue(occupied <= capacityOf(), "第 " + round + " 轮：占用不应超过容量");

            // 不变量：成功数 = 订单数 = 占用 = CREATE 日志数
            int orderCount = orderCount();
            assertEquals((int) ok, orderCount,
                    "第 " + round + " 轮：成功数应与订单数一致");
            assertEquals(orderCount, occupied,
                    "第 " + round + " 轮：占用应与有效订单数相符");
            assertEquals(orderCount, createLogCount(),
                    "第 " + round + " 轮：CREATE 日志数应与订单数相符");
        }
    }

    // ==================== 辅助 ====================

    private int disableService() throws Exception {
        return mockMvc.perform(put("/api/services/" + serviceId + "/disable")
                        .header("scc-test-token", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus();
    }

    private int bookStatus() throws Exception {
        return bookStatusWithId(UUID.randomUUID().toString());
    }

    private int bookStatusWithId(String requestId) throws Exception {
        String payload = """
                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                 "address":"窗口测试地址","requestId":"%s"}
                """.formatted(slotId, requestId);
        return mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn().getResponse().getStatus();
    }

    private boolean disableCommitted() {
        return !isServiceEnabled();
    }

    private boolean isServiceEnabled() {
        return db.queryForObject(
                "SELECT enabled FROM service_item WHERE id = ?", Boolean.class, serviceId);
    }

    private int occupiedOf() {
        return db.queryForObject(
                "SELECT occupied_count FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private int capacityOf() {
        return db.queryForObject(
                "SELECT capacity FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private int orderCount() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM service_order WHERE slot_id = ?", Integer.class, slotId);
    }

    /**
     * 该时段的必要 CREATE 日志数。
     *
     * <p>原工单要求并发测试「不只检查 HTTP 状态」，还要核对必要日志：
     * 订单数与 CREATE 日志数不相等，说明存在「建了单但没写日志」的半成品。
     */
    private int createLogCount() {
        return db.queryForObject(
                """
                SELECT COUNT(*) FROM service_order_log log
                JOIN service_order o ON o.id = log.order_id
                WHERE o.slot_id = ? AND log.action = 'CREATE'
                """, Integer.class, slotId);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size() + 2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 建一个未来时段，返回其真实 ID。
     *
     * <p>用 {@code ServiceSlotMapper.insert} 让 MyBatis-Plus 回填主键。
     *
     * <p><b>不再用 {@code SELECT LAST_INSERT_ID()}</b>：
     * 它返回<b>当前连接</b>上一次自增的值，而 {@code JdbcTemplate} 的
     * {@code update} 与 {@code queryForObject} 从连接池取连接，
     * <b>两次不保证是同一条</b>。全量并发时就会拿到别的连接上的值，
     * 表现为「时段容量不对」「订单建到别人的时段上」这类极难定位的失败。
     */
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

}