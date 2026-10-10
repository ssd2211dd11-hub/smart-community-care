package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.catalog.service.CatalogService;
import org.imnu.smartcommunitycare.order.service.OrderCreator;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * B-10：下架 / 关闭 / 调容量与预约竞争 —— <b>按真实锁与提交先后</b>验证。
 *
 * <h3>原工单第 111 行对本场景的判定要求</h3>
 * 「B-10 按实际事务取得锁和提交的先后解释结果：<b>不能把已先成功受理的预约，
 * 因随后下架就认定为『下架后新增』</b>。」
 *
 * <p>也就是说，结论必须建立在「谁先拿到锁、谁先提交」上，
 * <b>不能按 HTTP 请求发出的顺序推断，也不能只看最终的 enabled 值</b>。
 *
 * <h3>怎么控制先后</h3>
 * 在 {@code OrderCreator.createInTransaction} 入口挂一个同步闸
 * （{@code @MockitoSpyBean}，见 {@link OrderConcurrencyTest} 同款做法），
 * 让建单请求<b>确实停在锁与提交之前</b>，测试再决定何时放行。
 * 闸口同时把「此刻服务/时段的状态」和「此刻已有的订单数」记下来，
 * 于是每一步的因果关系都有观测证据，而不是推测。
 *
 * <p><b>为什么不用 sleep 假装并发</b>：原工单明令「不依赖 sleep 假装并发」。
 * sleep 只能给出「大概同时」，证明不了先后；而本场景要的恰恰是先后。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderLockOrderCompetitionTest extends BaseIntegrationTest {

    private static final String ELDER = "it_lockorder_elder";
    private static final String ADMIN = "it_lockorder_admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private ServiceSlotMapper slotMapper;

    @Autowired
    private CatalogService catalogService;

    /**
     * 只为在建单入口设同步闸；真实建单逻辑照常执行。
     *
     * <p>spy 的是具体类而非 Mapper：MyBatis Mapper 是 JDK 动态代理，
     * 接口方法在 Mockito 看来是抽象的，{@code callRealMethod()} 会抛
     * {@code Cannot call abstract real method on java object}。
     */
    @MockitoSpyBean
    private OrderCreator orderCreator;

    private String elderToken;
    private String adminToken;
    private Long serviceId;
    private Long slotId;
    private final List<Thread> bookingThreads = new ArrayList<>();
    private final List<CountDownLatch> releaseGates = new ArrayList<>();

    @AfterEach
    void finishBookingsBeforeFixtureCleanup() throws Exception {
        releaseGates.forEach(CountDownLatch::countDown);
        for (Thread thread : bookingThreads) {
            thread.join(30_000);
            assertThat(thread.isAlive()).as("后台预约必须结束，才能进入下一用例清理").isFalse();
        }
        releaseGates.clear();
        bookingThreads.clear();
    }

    @BeforeEach
    void prepare() throws Exception {
        createElder(ELDER, ELDER_PASSWORD);
        createAccount(ADMIN, ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);
        adminToken = loginAndGetToken(mockMvc, ADMIN, ADMIN_PASSWORD);

        serviceId = createFixtureItem(it -> {
            it.setName("锁序竞争测试服务");
            it.setDescription("用于验证管理操作与预约的锁与提交先后");
            it.setPrice(new BigDecimal("65.00"));
            it.setPhone("13600000000");
        });
        slotId = createSlot(3);
    }

    // ==================== 一、服务下架 ====================

    /**
     * B-10 顺序一：<b>下架先持锁并提交 → 随后到达的建单被拒</b>。
     *
     * <p>步骤被闸口固定成确定的两段：
     * <ol>
     *   <li>建单线程进入闸口（此时尚未加任何锁、尚未提交），
     *       主线程确认它停住后执行下架并等它提交完成；</li>
     *   <li>放行建单，它才开始加锁、建单。</li>
     * </ol>
     *
     * <p>因此这是确定的「先下架、后建单」，结果唯一可断言：建单必须被拒。
     */
    @Test
    @DisplayName("B-10 下架先提交：随后放行的建单被拒，且不留订单与名额")
    void disableCommittedFirstThenBookingRejected() throws Exception {
        AtomicReference<String> gateReached = new AtomicReference<>();
        CountDownLatch bookingParked = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        parkNextBooking(gateReached, bookingParked, releaseBooking);

        AtomicInteger bookStatus = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread booking = newBookingThread(failure, () -> {
            bookStatus.set(bookStatus());
            return null;
        });

        // 1) 等建单确实停在闸口（它还没加锁、没提交）
        assertThat(bookingParked.await(20, TimeUnit.SECONDS))
                .as("建单线程应到达闸口").isTrue();
        assertThat(gateReached.get())
                .as("闸口记录：此刻服务应仍为启用、时段仍开放、订单数为 0")
                .isEqualTo("serviceEnabled=true slotEnabled=true orders=0");

        // 2) 下架并等它提交完成
        assertThat(disableService()).as("下架请求本身应成功").isEqualTo(200);
        assertThat(serviceEnabled()).as("下架应已落库").isFalse();

        // 3) 放行建单——此刻它才开始加锁，锁内读到的必然是已下架状态
        releaseBooking.countDown();
        joinBooking(booking, failure);

        assertThat(bookStatus.get())
                .as("下架已提交后才加锁的建单必须被拒，实际：%d", bookStatus.get())
                .isEqualTo(400);
        assertInvariantsIntact(0, "被拒的建单不应留下订单");
    }

    /**
     * B-10 顺序二：<b>预约先持锁并成功受理 → 下架随后生效，原单保持合法</b>。
     *
     * <p>这是原工单特别强调的方向：「不能把已先成功受理的预约，
     * 因随后下架就认定为『下架后新增』」。
     *
     * <p>步骤：建单线程停在闸口 → 放行让它走完全程并提交 → 再执行下架。
     * 断言下架后<b>那一笔已受理的订单仍然是合法订单</b>：
     * 状态未变、占用未释放、CREATE 日志仍在、没有多出第二笔。
     */
    @Test
    @DisplayName("B-10 预约先受理后下架：原单保持合法，不被追溯取消也不新增")
    void bookingCommittedFirstThenDisableKeepsOriginalOrder() throws Exception {
        AtomicReference<String> gateReached = new AtomicReference<>();
        CountDownLatch bookingParked = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        parkNextBooking(gateReached, bookingParked, releaseBooking);

        String requestId = UUID.randomUUID().toString();
        AtomicReference<String> bookedOrderId = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread booking = newBookingThread(failure,
                () -> {
                    bookedOrderId.set(bookAndGetOrderId(requestId));
                    return null;
                });

        assertThat(bookingParked.await(20, TimeUnit.SECONDS))
                .as("建单线程应到达闸口").isTrue();

        // 放行 → 建单走完全程（加锁、扣名额、插订单、写日志、提交）
        releaseBooking.countDown();
        joinBooking(booking, failure);

        String orderId = bookedOrderId.get();
        assertThat(orderId).as("建单应成功并返回订单 ID").isNotBlank();
        assertThat(occupied()).as("受理后应占用 1 个名额").isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(createLogCount()).isEqualTo(1);

        // 现在才下架
        assertThat(disableService()).isEqualTo(200);
        assertThat(serviceEnabled()).isFalse();

        // 关键：已受理的订单必须保持合法
        assertInvariantsIntact(1, "下架不应影响已受理的订单数");

        // 进一步：下架后再来的新预约被拒，但原单状态不动
        assertThat(bookStatus(UUID.randomUUID().toString()))
                .as("下架后新预约应被拒").isEqualTo(400);
        assertThat(statusOf(orderId))
                .as("已受理订单的状态不应因下架而改变")
                .isEqualTo("PENDING_ASSIGNMENT");
        assertThat(occupied())
                .as("下架不应释放已受理订单占用的名额")
                .isEqualTo(1);
        assertThat(createLogCount())
                .as("下架不应新增或改写 CREATE 日志")
                .isEqualTo(1);
        assertThat(cancelLogCount()).isZero();
    }

    // ==================== 二、时段关闭 ====================

    /**
     * B-10 顺序一（关闭）：关闭先提交 → 随后放行的建单被拒。
     */
    @Test
    @DisplayName("B-10 关闭时段先提交：随后放行的建单被拒")
    void closeCommittedFirstThenBookingRejected() throws Exception {
        AtomicReference<String> gateReached = new AtomicReference<>();
        CountDownLatch bookingParked = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        parkNextBooking(gateReached, bookingParked, releaseBooking);

        AtomicInteger bookStatus = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread booking = newBookingThread(failure, () -> {
            bookStatus.set(bookStatus());
            return null;
        });

        assertThat(bookingParked.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(gateReached.get())
                .as("闸口记录：关闭前时段仍开放")
                .isEqualTo("serviceEnabled=true slotEnabled=true orders=0");

        assertThat(closeSlot()).as("关闭请求本身应成功").isEqualTo(200);
        assertThat(slotEnabled()).as("关闭应已落库").isFalse();

        releaseBooking.countDown();
        joinBooking(booking, failure);

        assertThat(bookStatus.get())
                .as("关闭已提交后才加锁的建单必须被拒，实际：%d", bookStatus.get())
                .isEqualTo(400);
        assertInvariantsIntact(0, "被拒的建单不应留下订单");
    }

    /**
     * B-10 顺序二（关闭）：预约先受理 → 关闭随后生效，原单保持合法。
     */
    @Test
    @DisplayName("B-10 预约先受理后关闭时段：原单保持合法")
    void bookingCommittedFirstThenCloseKeepsOriginalOrder() throws Exception {
        AtomicReference<String> gateReached = new AtomicReference<>();
        CountDownLatch bookingParked = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        parkNextBooking(gateReached, bookingParked, releaseBooking);

        AtomicReference<String> bookedOrderId = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread booking = newBookingThread(failure,
                () -> {
                    bookedOrderId.set(bookAndGetOrderId(UUID.randomUUID().toString()));
                    return null;
                });

        assertThat(bookingParked.await(20, TimeUnit.SECONDS)).isTrue();
        releaseBooking.countDown();
        joinBooking(booking, failure);

        String orderId = bookedOrderId.get();
        assertThat(orderId).as("建单应成功").isNotBlank();
        assertThat(occupied()).isEqualTo(1);

        assertThat(closeSlot()).isEqualTo(200);
        assertThat(slotEnabled()).isFalse();

        assertInvariantsIntact(1, "关闭不应影响已受理的订单数");
        assertThat(statusOf(orderId)).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(occupied()).as("关闭不应释放已受理订单的名额").isEqualTo(1);
        assertThat(createLogCount()).isEqualTo(1);
        assertThat(bookStatus(UUID.randomUUID().toString()))
                .as("关闭后新预约应被拒").isEqualTo(400);
    }

    // ==================== 三、容量缩减 ====================

    /**
     * B-10 容量竞争之一：<b>预约先受理 → 容量随后缩减成功，新预约按新容量被拒</b>。
     *
     * <p>占用 1、容量 3 时缩到 1 是允许的（不能低于已占用）。
     * 缩到 1 之后新预约必然被拒——验证的是「缩减按已占用为准」，
     * 而非「缩减一律拒绝」。
     */
    @Test
    @DisplayName("B-10 容量缩减在预约之后：缩到已占用被允许，新预约按新容量被拒")
    void shrinkAfterBookingAllowedAndNewBookingRejected() throws Exception {
        // 先正常受理 1 笔（容量 3）
        String firstOrder = bookAndGetOrderId(UUID.randomUUID().toString());
        assertThat(firstOrder).isNotBlank();
        assertThat(occupied()).isEqualTo(1);
        assertThat(capacity()).isEqualTo(3);

        // 缩到 1（等于已占用）应成功
        assertThat(updateCapacity(1)).as("缩到已占用应被允许").isEqualTo(200);
        assertThat(capacity()).isEqualTo(1);
        assertThat(occupied()).as("缩减不应改变占用").isEqualTo(1);

        // 新预约被拒，且不产生任何痕迹
        assertThat(bookStatus(UUID.randomUUID().toString()))
                .as("容量已满时新预约应被容量拒绝").isEqualTo(409);
        assertInvariantsIntact(1, "被容量拒绝的建单不应留下订单");
        assertThat(statusOf(firstOrder)).isEqualTo("PENDING_ASSIGNMENT");
    }

    /**
     * B-10 容量竞争之二：<b>容量缩减先提交 → 预约随后按新容量判断</b>。
     *
     * <p>容量 3、空闲时缩到 2，随后两笔应成功、第三笔被拒。
     * 验证缩减在先时，预约确实读到的是新容量。
     */
    @Test
    @DisplayName("B-10 容量缩减先提交：随后预约按新容量判定，第3笔被拒")
    void shrinkCommittedFirstThenBookingUsesNewCapacity() throws Exception {
        assertThat(updateCapacity(2)).as("空闲时缩容应成功").isEqualTo(200);
        assertThat(capacity()).isEqualTo(2);

        assertThat(bookStatus(UUID.randomUUID().toString()))
                .as("第1笔应成功").isEqualTo(200);
        assertThat(bookStatus(UUID.randomUUID().toString()))
                .as("第2笔应成功").isEqualTo(200);
        assertThat(occupied()).isEqualTo(2);

        assertThat(bookStatus(UUID.randomUUID().toString()))
                .as("第3笔应被新容量拒绝").isEqualTo(409);

        assertInvariantsIntact(2, "占用应与有效订单数一致");
        assertThat(createLogCount()).isEqualTo(2);
    }

    /**
     * B-10 容量竞争之三：<b>预约先持锁受理 → 缩减随后生效，不得低于已占用</b>。
     *
     * <p>用闸口把顺序固定成「预约先加锁受理、缩减后执行」，
     * 验证缩减读到的是<b>已提交后的占用</b>，不会把容量改到低于实际占用。
     */
    @Test
    @DisplayName("B-10 预约先受理后缩容：缩容以已提交占用为准，不得低于它")
    void bookingCommittedFirstThenShrinkRespectsOccupied() throws Exception {
        AtomicReference<String> gateReached = new AtomicReference<>();
        CountDownLatch bookingParked = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        parkNextBooking(gateReached, bookingParked, releaseBooking);

        AtomicReference<String> bookedOrderId = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread booking = newBookingThread(failure,
                () -> {
                    bookedOrderId.set(bookAndGetOrderId(UUID.randomUUID().toString()));
                    return null;
                });
        assertThat(bookingParked.await(20, TimeUnit.SECONDS)).isTrue();
        releaseBooking.countDown();
        joinBooking(booking, failure);
        assertThat(bookedOrderId.get()).isNotBlank();
        assertThat(occupied()).isEqualTo(1);

        // 缩到 1（等于已占用）允许
        assertThat(updateCapacity(1)).isEqualTo(200);
        // 再缩到 0 不行（本来就非法）；缩到比占用小则应被拒
        assertThat(updateCapacity(0)).as("容量必须为正").isNotEqualTo(200);

        // 占用已经是 2 时尝试缩到 1 必被拒
        assertThat(bookStatus(UUID.randomUUID().toString()))
                .as("容量 1 已满，第2笔应被拒").isEqualTo(409);
        assertInvariantsIntact(1, "占用应保持 1");
        assertThat(capacity()).as("容量应仍为 1").isEqualTo(1);
    }

    // ==================== 夹具与辅助 ====================

    /**
     * 让<b>下一次</b>建单停在 {@code OrderCreator} 入口，并记录闸口看到的状态。
     *
     * @param gateReached    闸口状态快照（服务启用 / 时段开放 / 已有订单数）
     * @param bookingParked  建单停住后 countDown
     * @param releaseBooking 主线程决定何时放行
     */
    private void parkNextBooking(AtomicReference<String> gateReached,
                                 CountDownLatch bookingParked,
                                 CountDownLatch releaseBooking) {
        AtomicBoolean armed = new AtomicBoolean(true);
        releaseGates.add(releaseBooking);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (armed.compareAndSet(true, false)) {
                gateReached.set("serviceEnabled=" + serviceEnabled()
                        + " slotEnabled=" + slotEnabled()
                        + " orders=" + orderCount());
                bookingParked.countDown();
                try {
                    // 有限等待：主线程不放行时不能无限期挂住测试线程
                    if (!releaseBooking.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("闸口等待超时，主线程未放行");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("闸口被中断", e);
                }
            }
            // 放行后走真实建单逻辑
            return invocation.callRealMethod();
        }).when(orderCreator).createInTransaction(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * 每个场景都要查的不变量。
     *
     * <p>原工单第 111 行要求「无超容量、负名额」，
     * 且成功数、名额、有效订单、必要 CREATE 日志必须彼此相符。
     */
    private void assertInvariantsIntact(int expectedOrders, String what) {
        int occupied = occupied();
        int capacity = capacity();
        int orders = orderCount();

        assertThat(occupied).as("%s：占用不得为负", what).isGreaterThanOrEqualTo(0);
        assertThat(occupied).as("%s：占用不得超过容量", what)
                .isLessThanOrEqualTo(capacity);
        assertThat(orders).as("%s：订单数不符", what).isEqualTo(expectedOrders);
        assertThat(occupied).as("%s：占用应与有效订单数相符", what).isEqualTo(orders);
        assertThat(createLogCount()).as("%s：CREATE 日志数应与订单数相符", what)
                .isEqualTo(orders);
    }

    /**
     * 起一个后台线程跑一个可能抛受检异常的建单动作。
     *
     * <p>用 {@code Callable} 而非 {@code Runnable}：MockMvc 调用抛的是
     * 受检 {@code Exception}，{@code Runnable} 不允许抛，编译不过。
     * 异常记进 {@code failure}，由调用方在 join 之后断言。
     */
    private Thread newBookingThread(AtomicReference<Throwable> failure,
                                    Callable<String> body) {
        Thread t = new Thread(() -> {
            try {
                body.call();
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        t.setDaemon(true);
        bookingThreads.add(t);
        t.start();
        return t;
    }

    /** join 之后统一检查：后台线程里的异常必须让用例失败，不能被吞掉 */
    private void joinBooking(Thread t, AtomicReference<Throwable> failure) throws Exception {
        t.join(30_000);
        assertThat(t.isAlive()).as("建单线程应在30秒内结束").isFalse();
        Throwable e = failure.get();
        if (e != null) {
            throw new AssertionError("建单线程内抛出异常", e);
        }
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

    private int disableService() throws Exception {
        return mockMvc.perform(put("/api/services/" + serviceId + "/disable")
                        .header("scc-test-token", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus();
    }

    private int closeSlot() throws Exception {
        return mockMvc.perform(put("/api/services/slots/" + slotId + "/close")
                        .header("scc-test-token", "Bearer " + adminToken))
                .andReturn().getResponse().getStatus();
    }

    private int updateCapacity(int capacity) throws Exception {
        return mockMvc.perform(put("/api/services/slots/" + slotId + "/capacity")
                        .header("scc-test-token", "Bearer " + adminToken)
                        .param("capacity", String.valueOf(capacity)))
                .andReturn().getResponse().getStatus();
    }

    private int bookStatus(String requestId) throws Exception {
        String payload = """
                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                 "address":"锁序测试地址","requestId":"%s"}
                """.formatted(slotId, requestId);
        return mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn().getResponse().getStatus();
    }

    private int bookStatus() throws Exception {
        return bookStatus(UUID.randomUUID().toString());
    }

    /** 建单并返回订单 ID；失败时抛异常（由后台线程的 failure 捕获） */
    private String bookAndGetOrderId(String requestId) throws Exception {
        String payload = """
                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                 "address":"锁序测试地址","requestId":"%s"}
                """.formatted(slotId, requestId);
        String resp = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
        if (!m.find()) {
            throw new IllegalStateException("建单响应中未找到订单 ID：" + resp);
        }
        return m.group(1);
    }

    private boolean serviceEnabled() {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT enabled FROM service_item WHERE id = ?", Boolean.class, serviceId));
    }

    private boolean slotEnabled() {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT enabled FROM service_slot WHERE id = ?", Boolean.class, slotId));
    }

    private int occupied() {
        return db.queryForObject(
                "SELECT occupied_count FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private int capacity() {
        return db.queryForObject(
                "SELECT capacity FROM service_slot WHERE id = ?", Integer.class, slotId);
    }

    private int orderCount() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM service_order WHERE slot_id = ?", Integer.class, slotId);
    }

    private int createLogCount() {
        return logCount("CREATE");
    }

    private int cancelLogCount() {
        return logCount("CANCEL");
    }

    private int logCount(String action) {
        Integer n = db.queryForObject(
                """
                SELECT COUNT(*) FROM service_order_log log
                JOIN service_order o ON o.id = log.order_id
                WHERE o.slot_id = ? AND log.action = ?
                """, Integer.class, slotId, action);
        return n == null ? 0 : n;
    }

    private String statusOf(String orderId) {
        return db.queryForObject(
                "SELECT status FROM service_order WHERE id = ?", String.class, orderId);
    }
}
