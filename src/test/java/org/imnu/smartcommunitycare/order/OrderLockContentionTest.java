package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通过正常API和两个真实事务验证服务与时段锁等待。
 * 外层事务只延迟提交，不替换业务SQL；performance_schema用于确认实际等待，再释放先行事务。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderLockContentionTest extends BaseIntegrationTest {

    enum ManagementAction { DISABLE, CLOSE, SHRINK }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceSlotMapper slotMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String elderToken;
    private String adminToken;
    private Long serviceId;
    private Long slotId;
    private long originalOrderId;

    @BeforeEach
    void prepare() throws Exception {
        createElder("it_controller_lock_elder", ELDER_PASSWORD);
        createAccount("it_controller_lock_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        elderToken = loginAndGetToken(mockMvc, "it_controller_lock_elder", ELDER_PASSWORD);
        adminToken = loginAndGetToken(mockMvc, "it_controller_lock_admin", ADMIN_PASSWORD);
        serviceId = createFixtureItem(item -> {
            item.setName("锁竞争验收服务");
            item.setDescription("真实锁等待夹具");
            item.setPrice(new BigDecimal("30.00"));
            item.setPhone("13900000000");
        });
        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(serviceId);
        slot.setStartAt(LocalDateTime.now().plusDays(1));
        slot.setEndAt(LocalDateTime.now().plusDays(1).plusHours(1));
        slot.setCapacity(2);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(slot);
        assertThat(slot.getId()).isNotNull();
        slotId = slot.getId();

        // 已受理的基线订单在全部竞争场景中都必须继续有效。
        String body = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingPayload(UUID.randomUUID().toString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        originalOrderId = JSON.readTree(body).path("data").path("id").asLong();
        assertThat(originalOrderId).isPositive();
        assertCounts(1, 2);
    }

    @ParameterizedTest(name = "B-10 管理先持锁：{0} 提交前预约必须等待，提交后按新状态拒绝")
    @EnumSource(ManagementAction.class)
    void managementHoldsLockFirst(ManagementAction action) throws Exception {
        verifyContendedRequests(action, false);
    }

    @ParameterizedTest(name = "B-10 预约先持锁：{0} 必须等待受理提交，不得破坏已有订单")
    @EnumSource(ManagementAction.class)
    void bookingHoldsLockFirst(ManagementAction action) throws Exception {
        verifyContendedRequests(action, true);
    }

    private void verifyContendedRequests(ManagementAction action, boolean bookingFirst)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ownerHasLocks = new CountDownLatch(1);
        CountDownLatch allowOwnerCommit = new CountDownLatch(1);
        AtomicLong ownerConnectionId = new AtomicLong();
        String requestId = UUID.randomUUID().toString();
        Future<Integer> owner = null;
        Future<Integer> contender = null;
        int observedWaits = 0;
        try {
            owner = pool.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                try {
                    int code = bookingFirst ? book(requestId) : manage(action);
                    assertThat(code).as("先行请求应成功，提交暂由测试闸控制").isEqualTo(200);
                    ownerConnectionId.set(jdbcTemplate.queryForObject(
                            "SELECT CONNECTION_ID()", Long.class));
                    ownerHasLocks.countDown();
                    if (!allowOwnerCommit.await(20, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("先行事务等待放行超时");
                    }
                    return code;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("先行事务被中断", e);
                } catch (Exception e) {
                    throw new IllegalStateException("先行 API 请求失败", e);
                }
            }));
            assertThat(ownerHasLocks.await(20, TimeUnit.SECONDS))
                    .as("先行请求已完成真实数据库变更并持有未提交的锁").isTrue();
            contender = pool.submit(() -> bookingFirst ? manage(action) : book(requestId));

            // 条件来自数据库锁等待表，轮询时间不承担制造竞争的作用。
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(waitingForConnection(ownerConnectionId.get()))
                            .as("后行请求必须实际等待先行连接持有的业务行锁").isPositive());
            observedWaits = waitingForConnection(ownerConnectionId.get());
            assertThat(contender.isDone()).as("持锁事务未提交时后行请求不应完成").isFalse();
            allowOwnerCommit.countDown();
            assertThat(owner.get(20, TimeUnit.SECONDS)).isEqualTo(200);
            int contenderStatus = contender.get(20, TimeUnit.SECONDS);

            if (bookingFirst) {
                assertThat(contenderStatus).isEqualTo(action == ManagementAction.SHRINK ? 409 : 200);
                assertCounts(2, 2);
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM service_order WHERE slot_id=? AND request_id=?"
                                + " AND status='PENDING_ASSIGNMENT'",
                        Integer.class, slotId, requestId)).isEqualTo(1);
            } else {
                assertThat(contenderStatus).isEqualTo(action == ManagementAction.SHRINK ? 409 : 400);
                assertCounts(1, action == ManagementAction.SHRINK ? 1 : 2);
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM service_order WHERE slot_id=? AND request_id=?",
                        Integer.class, slotId, requestId)).isZero();
            }
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM service_order WHERE id=?", String.class, originalOrderId))
                    .isEqualTo("PENDING_ASSIGNMENT");
            if (action != ManagementAction.SHRINK) {
                assertThat(book(UUID.randomUUID().toString())).isEqualTo(400);
                assertCounts(bookingFirst ? 2 : 1, 2);
            }
            System.out.printf("B10_LOCK_CONTENTION action=%s bookingFirst=%s waits=%d%n",
                    action, bookingFirst, observedWaits);
        } finally {
            allowOwnerCommit.countDown();
            try {
                // 任一断言失败也先结束所有请求，下一用例才能清理共享夹具。
                if (owner != null) owner.get(30, TimeUnit.SECONDS);
            } finally {
                try {
                    if (contender != null) contender.get(30, TimeUnit.SECONDS);
                } finally {
                    pool.shutdownNow();
                    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
                            .as("竞争线程池必须结束").isTrue();
                }
            }
        }
    }

    private int waitingForConnection(long connectionId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits w
                JOIN performance_schema.data_locks pending
                  ON pending.ENGINE=w.ENGINE
                 AND pending.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                JOIN performance_schema.threads blocker
                  ON blocker.THREAD_ID=w.BLOCKING_THREAD_ID
                WHERE blocker.PROCESSLIST_ID=?
                  AND pending.OBJECT_SCHEMA=DATABASE()
                  AND pending.OBJECT_NAME IN ('service_item', 'service_slot')
                """, Integer.class, connectionId);
    }

    private void assertCounts(int expectedOrders, int expectedCapacity) {
        ServiceSlot slot = slotMapper.selectById(slotId);
        assertThat(slot.getCapacity()).isEqualTo(expectedCapacity);
        assertThat(slot.getOccupiedCount()).isEqualTo(expectedOrders)
                .isBetween(0, slot.getCapacity());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_order WHERE slot_id=?", Integer.class, slotId))
                .isEqualTo(expectedOrders);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM service_order_log log
                JOIN service_order o ON o.id=log.order_id
                WHERE o.slot_id=? AND log.action='CREATE'
                """, Integer.class, slotId)).isEqualTo(expectedOrders);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM service_order_log log
                JOIN service_order o ON o.id=log.order_id
                WHERE o.slot_id=? AND log.action='CANCEL'
                """, Integer.class, slotId)).isZero();
    }

    private int manage(ManagementAction action) throws Exception {
        var request = switch (action) {
            case DISABLE -> put("/api/services/" + serviceId + "/disable");
            case CLOSE -> put("/api/services/slots/" + slotId + "/close");
            case SHRINK -> put("/api/services/slots/" + slotId + "/capacity").param("capacity", "1");
        };
        return mockMvc.perform(request.header("scc-test-token", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus();
    }

    private int book(String requestId) throws Exception {
        return mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingPayload(requestId)))
                .andReturn().getResponse().getStatus();
    }

    private String bookingPayload(String requestId) {
        return """
                {"slotId":%d,"contactName":"锁竞争复验","contactPhone":"13900000003",
                 "address":"测试夹具地址","requestId":"%s"}
                """.formatted(slotId, requestId);
    }
}
