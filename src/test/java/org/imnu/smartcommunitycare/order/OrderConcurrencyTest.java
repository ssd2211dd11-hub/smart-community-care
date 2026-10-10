package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceItemMapper;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.service.OrderCreator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实并发请求验证容量不超发、同键幂等、异内容拒绝、满额后原请求确认及取消只释放一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderConcurrencyTest extends BaseIntegrationTest {

    private static final String ELDER = "it_conc_elder";
    private static final int CAPACITY = 2;
    private static final int CONCURRENT_REQUESTS = 20;

    /** B-02 需要容量足够大，避免被容量规则拦下而测不到防重 */
    private static final int CAPACITY_FOR_SAME_KEY_CASE = 50;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceItemMapper itemMapper;

    @Autowired
    private ServiceSlotMapper slotMapper;

    /**
     * 只 spy 这一层，为的是在「已确认查不到原单、即将建单」这个点上设同步闸。
     *
     * <p>见 {@link #sameRequestIdAtCapacityOneStillReturnsOriginalOrder}。
     * <b>为什么 spy 的是 {@code OrderCreator} 而不是 {@code ServiceOrderMapper}：</b>
     * MyBatis 的 Mapper 是 JDK 动态代理，接口方法在 Mockito 里是抽象的，
     * {@code callRealMethod()} 会抛
     * {@code Cannot call abstract real method on java object}。
     * {@code OrderCreator} 是具体类，spy 后仍能调用真实方法。
     *
     * <p><b>为什么这个位置能代表「前置查询已执行且查不到」：</b>
     * {@code OrderBookingService.create} 只在
     * {@code orderMapper.findByRequest(...)} 返回 {@code null} 时
     * 才会调用 {@code orderCreator.createInTransaction(...)}。
     * 也就是说，<b>能进入这个方法，就等价于前置防重查询已经查过且没查到</b>。
     * 用例里还会在闸口直接查一次库，把这个等价关系变成可观测的证据。
     */
    @MockitoSpyBean
    private OrderCreator orderCreator;

    /**
     * 真实订单 Mapper，B-05 需要读回持久状态做前后比对。
     *
     * <p>刻意与 {@link #orderCreator} 分开：那个是 spy（带同步闸），
     * 这个要的是未经改动的真实查询行为。
     */
    @Autowired
    private ServiceOrderMapper orderMapper;

    private String elderToken;
    private Long slotId;
    private Long serviceId;

    @BeforeEach
    void prepareSlot() throws Exception {
        createElder(ELDER, ELDER_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);

        Long factoryServiceId = createFixtureItem(it -> {
            it.setName("并发测试服务");
            it.setDescription("用于验证并发名额");
            it.setPrice(new BigDecimal("60.00"));
            it.setPhone("13800000000");
        });

        serviceId = factoryServiceId;
        slotId = createSlot(CAPACITY);
    }

    /**
     * B-01：20 个不同 requestId 的请求并发抢同一时段（容量 2）。
     *
     * <p>期望：恰好 2 笔成功、其余明确容量冲突；
     * 成功订单 2 笔、名额 2、创建日志 2 条。
     *
     * <p>注意：不能以「全部失败」来证明不超发——那说明功能坏了，不是防住了。
     */
    @Test
    @DisplayName("B-01 容量2时20个并发请求恰好成功2单，名额与订单一致")
    void concurrentBookingNeverExceedsCapacity() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final String requestId = UUID.randomUUID().toString();
            tasks.add(() -> {
                int status = book(requestId);
                if (status == 200) {
                    successCount.incrementAndGet();
                } else if (status == 409) {
                    conflictCount.incrementAndGet();
                }
                return status;
            });
        }

        runConcurrently(tasks);

        int occupied = slotMapper.selectById(slotId).getOccupiedCount();
        int orders = orderCount();
        int createLogs = countLogs("CREATE");

        // 三方口径必须一致：成功数 = 名额 = 订单数 = 创建日志数
        assertThat(successCount.get()).isEqualTo(CAPACITY);
        assertThat(occupied).isEqualTo(CAPACITY);
        assertThat(orders).isEqualTo(CAPACITY);
        assertThat(createLogs).isEqualTo(CAPACITY);
        // 其余请求必须都是明确的容量冲突，不能是别的错误码
        assertThat(conflictCount.get()).isEqualTo(CONCURRENT_REQUESTS - CAPACITY);
    }

    /**
     * B-01 扩展：容量 1、20 个不同 requestId 并发。
     *
     * <p><b>编号说明</b>：原工单的 B-01 只写「容量为 2」，
     * 容量 1 是本项的边界扩展，<b>不是 B-03</b>（B-03 是同键异内容）。
     * 此前把它标成 B-03 是错的。
     *
     * <p>容量 1 比容量 2 更严格：只允许 1 笔成功，且必须恰好 1 笔。
     */
    @Test
    @DisplayName("B-01扩展 容量1时不同requestId并发，恰好1单成功且名额为1")
    void capacityOneWithDifferentKeysYieldsExactlyOneOrder() throws Exception {
        long oneSlotId = createSlot(1);

        List<Callable<Integer>> tasks = new ArrayList<>();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger full = new AtomicInteger();

        for (int i = 0; i < 8; i++) {
            String rid = UUID.randomUUID().toString();
            tasks.add(() -> {
                int status = bookStatus(oneSlotId, rid);
                if (status == 200) success.incrementAndGet();
                else if (status == 409) full.incrementAndGet();
                return status;
            });
        }
        runConcurrently(tasks);

        assertThat(success.get()).as("容量1时应恰好 1 单成功").isEqualTo(1);
        assertThat(full.get()).as("其余应为容量冲突").isEqualTo(7);
        assertThat(slotMapper.selectById(oneSlotId).getOccupiedCount()).isEqualTo(1);
        assertThat(orderCount(oneSlotId)).isEqualTo(1);
        assertThat(countLogs(oneSlotId, "CREATE")).isEqualTo(1);
    }

    /**
     * B-02：同一 requestId、相同内容并发重试。
     *
     * <p>期望：所有成功响应指向同一订单，只占 1 个名额，只有 1 条创建日志。
     *
     * <p><b>容量必须放大到能容纳全部并发请求</b>（本用例 8 个）。
     * 否则第一个请求扣掉名额后，其余会被「容量已满」挡回 409，
     * 测到的就成了容量竞争而不是「同键重试」——那属于 B-01 要覆盖的场景。
     */
    @Test
    @DisplayName("B-02 同一requestId并发重试只占一个名额只有一条创建日志")
    void concurrentSameRequestIdOccupiesOnce() throws Exception {
        // 本用例只关心防重，需要一个容量足够大的**独立**时段。
        // 不能改共享的 slotId 指向的行——那会污染后续用例读到的名额值。
        long dedicatedSlotId = createSlot(CAPACITY_FOR_SAME_KEY_CASE);

        String sharedRequestId = UUID.randomUUID().toString();
        int attempts = 8;

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            tasks.add(() -> bookAndGetOrderId(dedicatedSlotId, sharedRequestId));
        }

        List<String> orderIds = runConcurrently(tasks);

        // 所有返回都必须是 200，且订单号唯一
        assertThat(orderIds).isNotEmpty();
        assertThat(orderIds.stream().distinct().count()).isEqualTo(1);

        assertThat(slotMapper.selectById(dedicatedSlotId).getOccupiedCount()).isEqualTo(1);
        assertThat(orderCount(dedicatedSlotId)).isEqualTo(1);
        assertThat(countLogs(dedicatedSlotId, "CREATE")).isEqualTo(1);
    }

    /**
     * B-02 边界 + <b>兜底路径的明确证据</b>：容量 1、同一 requestId 并发。
     *
     * <p>普通 B-02 用容量 50 的独立时段，<b>刻意绕开了满额</b>，
     * 所以它只证明「防重键 + 唯一约束兜住重复」，
     * 没覆盖「第一笔占满名额后，第二笔同一 requestId 该怎么回应」。
     *
     * <p>而在容量 1 下，第二笔必然拿不到名额。若它仍返回原订单，
     * 唯一可能的路径是：<b>名额冲突被捕获 → 回查原订单 → 返回它</b>。
     *
     * <h3>为什么需要这个同步闸（这是本用例的关键）</h3>
     * 「兜底路径被走到」需要证明：<b>至少两个独立请求都在原单存在之前
     * 走到了建单这一步</b>。只用「同一时刻起跑」证明不了——
     * 完全可能 T1 查不到→建单成功→提交，T2 才查到原单直接返回
     * （走的是幂等分支，根本没碰名额）。那样即使全绿，也没测到兜底。
     *
     * <p>做法：在 {@code OrderCreator.createInTransaction} 入口挂一个
     * <b>CyclicBarrier，前两个到达者各自等对方</b>。
     * 能进入这个方法，就等价于前置防重查询已经查过且没查到
     * （{@code OrderBookingService.create} 只在 findByRequest 返回 null 时
     * 才会调它，见字段注释）。两个请求都停在闸口时，
     * 数据库里必然还没有订单——它们随后才可能建单。
     *
     * <p><b>闸口额外查一次库</b>，把「原单此刻确实不存在」从推断变成观测：
     * 每个到达者记录自己看到的同 requestId 订单行数，最后断言全部为 0。
     *
     * <p>第三个及以后的请求不参与等待（barrier 只用一次），
     * 它们要么在放行后立即查到原单（幂等分支），要么与前两个竞争名额。
     */
    @Test
    @DisplayName("B-02边界 容量1时同requestId并发：证明兜底路径被真实走到且返回原订单")
    void sameRequestIdAtCapacityOneStillReturnsOriginalOrder() throws Exception {
        // 容量 1：这是本用例的全部意义
        long oneSlotId = createSlot(1);
        String sharedRequestId = UUID.randomUUID().toString();

        // 参与同步的请求数：2。必须 >= 2，否则 barrier 会等不到人。
        int gateWidth = 2;
        AtomicInteger gateArrivals = new AtomicInteger();
        // 每个到达闸口的请求，在那里看到的同 requestId 订单行数
        List<Integer> ordersSeenAtGate = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier preBookingBarrier = new CyclicBarrier(gateWidth);

        doAnswer(invocation -> {
            if (gateArrivals.incrementAndGet() <= gateWidth) {
                // 此刻直接查库。若这里已经能看到订单，
                // 说明前置查询本该命中、这个用例就没测到想测的东西。
                Integer visible = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM service_order WHERE request_id = ?",
                        Integer.class, sharedRequestId);
                ordersSeenAtGate.add(visible == null ? 0 : visible);
                try {
                    preBookingBarrier.await(20, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("建单前同步失败", e);
                }
            }
            // 真实建单逻辑照常执行（加锁、扣名额、插订单、写日志都在里面）
            return invocation.callRealMethod();
        }).when(orderCreator).createInTransaction(anyLong(), any(), anyString(), any());

        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> bookAndGetOrderId(oneSlotId, sharedRequestId));
        }
        List<String> orderIds = runConcurrently(tasks);

        // ---- 判据 1：兜底路径确实被走到 ----
        // 至少两个独立请求在「原单还不存在」时走到了建单入口。
        // 少于 2 就说明同步闸没起作用，用例没有测到想测的东西。
        assertThat(gateArrivals.get())
                .as("至少应有两个请求在原单不存在时走到建单入口，实际：%d", gateArrivals.get())
                .isGreaterThanOrEqualTo(gateWidth);
        assertThat(ordersSeenAtGate)
                .as("到达同步闸时，同 requestId 的订单必须一行都不存在")
                .hasSizeGreaterThanOrEqualTo(gateWidth)
                .allSatisfy(n -> assertThat(n).as("闸口看到的订单行数").isZero());

        // ---- 判据 2：结果仍然全部指向同一订单 ----
        // 若第二个请求拿到的是「名额已满」，说明它被当成新请求而非重试，
        // 兜底分支就等于没生效。
        assertThat(orderIds).isNotEmpty();
        assertThat(orderIds.stream().distinct().count())
                .as("同 requestId 的并发请求应全部指向同一订单，实际返回：%s", orderIds)
                .isEqualTo(1);

        // ---- 判据 3：名额 / 订单 / 日志各 1 ----
        assertThat(slotMapper.selectById(oneSlotId).getOccupiedCount()).isEqualTo(1);
        assertThat(orderCount(oneSlotId)).isEqualTo(1);
        assertThat(countLogs(oneSlotId, "CREATE")).isEqualTo(1);
    }

    /**
     * 同一请求标识提交不同内容时返回409，不新增订单或占用。
     */
    @Test
    @DisplayName("B-03 同requestId异内容返回409且不创建第二单、不占其他名额")
    void sameRequestIdDifferentContentRejected() throws Exception {
        long roomySlot = createSlot(CAPACITY_FOR_SAME_KEY_CASE);
        long otherSlot = createSlot(CAPACITY_FOR_SAME_KEY_CASE);
        String requestId = UUID.randomUUID().toString();

        assertThat(bookStatus(roomySlot, requestId)).isEqualTo(200);

        // 同一把键，换联系人内容（对应原工单的「改联系信息」）
        String changedContact = """
                {"slotId":%d,"contactName":"另一个人","contactPhone":"13900000002",
                 "address":"另一个地址","requestId":"%s"}
                """.formatted(roomySlot, requestId);
        assertThat(performBook(changedContact))
                .as("同键异内容必须 409").isEqualTo(409);

        // 原工单还写了「改时段」：换到另一个时段用同一把键，同样必须 409，
        // 且**那个时段的名额不能被占用**。
        String changedSlot = """
                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                 "address":"并发测试地址","requestId":"%s"}
                """.formatted(otherSlot, requestId);
        assertThat(performBook(changedSlot))
                .as("同键改时段必须 409").isEqualTo(409);

        // 原时段：名额、订单、日志都不变
        assertThat(slotMapper.selectById(roomySlot).getOccupiedCount()).isEqualTo(1);
        assertThat(orderCount(roomySlot)).isEqualTo(1);
        assertThat(countLogs(roomySlot, "CREATE")).isEqualTo(1);

        // 另一个时段：名额必须是 0——被拒绝的提交不得留下痕迹
        assertThat(slotMapper.selectById(otherSlot).getOccupiedCount())
                .as("被 409 拒绝的换时段提交不得占用其他时段名额").isZero();
        assertThat(orderCount(otherSlot)).isZero();
        assertThat(countLogs(otherSlot, "CREATE")).isZero();
    }

    /**
     * 创建成功后名额被占满，原请求重放仍返回原订单，不增加占用。
     */
    @Test
    @DisplayName("B-04 首次成功后容量被占满，原请求重放仍返回原订单且不额外占用")
    void replayOfSuccessfulKeyAfterSlotFullReturnsOriginalOrder() throws Exception {
        // 容量 2 的独立时段
        long targetSlot = createSlot(2);
        String myRequestId = UUID.randomUUID().toString();

        // 第一步：真实创建成功，保存原键 / 内容 / 订单 ID
        String originalOrderId = bookAndGetOrderId(targetSlot, myRequestId);
        assertThat(originalOrderId).isNotBlank();

        // 第二步：把剩余名额占满（用另一把键）
        assertThat(bookStatus(targetSlot, UUID.randomUUID().toString()))
                .as("占满剩余名额的请求应成功").isEqualTo(200);
        assertThat(slotMapper.selectById(targetSlot).getOccupiedCount()).isEqualTo(2);
        assertThat(bookStatus(targetSlot, UUID.randomUUID().toString()))
                .as("容量确已占满").isEqualTo(409);

        // 第三步：用原键、原内容重放。必须 200 且为原 ID。
        String replayed = bookAndGetOrderId(targetSlot, myRequestId);
        assertThat(replayed)
                .as("原请求重放必须返回原订单 ID，而不是新建或报名额不足")
                .isEqualTo(originalOrderId);

        // 无额外占用：名额、订单、日志都不增
        assertThat(slotMapper.selectById(targetSlot).getOccupiedCount()).isEqualTo(2);
        assertThat(orderCount(targetSlot)).isEqualTo(2);
        assertThat(countLogs(targetSlot, "CREATE")).isEqualTo(2);
    }

    /**
     * B-04 补充：满额时原请求重放<b>不是靠兜底路径</b>，
     * 而是靠前置防重查询直接命中。
     *
     * <p>与容量 1 那个用例的差别：那里原单是在本次并发中才建成的，
     * 后来者必须走「名额冲突 → 回查」；这里原单<b>早就存在</b>，
     * 请求在第一道防重查询就被挡下，根本走不到名额那一步。
     *
     * <p>把两种情形分开测，才能证明兜底路径真的被覆盖到了
     * （见 {@link #sameRequestIdAtCapacityOneStillReturnsOriginalOrder}）。
     */
    @Test
    @DisplayName("B-04补充 满额后原请求重放不经过名额判断：占用与日志均不增")
    void replayOfExistingKeyDoesNotTouchSlotAtAll() throws Exception {
        long targetSlot = createSlot(1);
        String myRequestId = UUID.randomUUID().toString();

        String originalOrderId = bookAndGetOrderId(targetSlot, myRequestId);
        assertThat(slotMapper.selectById(targetSlot).getOccupiedCount()).isEqualTo(1);

        // 已被取消？不——这里是已满。连续重放 3 次，结果必须完全稳定。
        for (int i = 0; i < 3; i++) {
            assertThat(bookAndGetOrderId(targetSlot, myRequestId))
                    .as("第 %d 次重放应稳定返回原订单", i + 1)
                    .isEqualTo(originalOrderId);
        }

        assertThat(slotMapper.selectById(targetSlot).getOccupiedCount()).isEqualTo(1);
        assertThat(orderCount(targetSlot)).isEqualTo(1);
        assertThat(countLogs(targetSlot, "CREATE")).isEqualTo(1);
    }

    /**
     * 取消后的原创建请求返回已取消订单，不复活订单或重新占位。
     */
    @Test
    @DisplayName("B-05 取消后重放原创建请求：仍为原ID且已取消，不复活不占位")
    void replayAfterCancelReturnsCancelledOrderWithoutResurrecting() throws Exception {
        long targetSlot = createSlot(2);
        String requestId = UUID.randomUUID().toString();

        // 先真实创建成功
        String originalOrderId = bookAndGetOrderId(targetSlot, requestId);
        assertThat(slotMapper.selectById(targetSlot).getOccupiedCount()).isEqualTo(1);

        // 记录取消前的状态，用于事后比对「取消时间没被改写」
        ServiceOrder beforeCancel = orderMapper.selectById(Long.valueOf(originalOrderId));
        assertThat(beforeCancel.getStatus()).isEqualTo("PENDING_ASSIGNMENT");

        // 经真实取消接口取消成功
        int cancelStatus = mockMvc.perform(
                        post("/api/service-orders/" + originalOrderId + "/cancel")
                                .header("scc-test-token", "Bearer " + elderToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(withOrderVersion(originalOrderId, """
                                        {"reason":"B-05 取消后重放"}
                                        """)))
                .andReturn().getResponse().getStatus();
        assertThat(cancelStatus).as("取消应成功").isEqualTo(200);

        ServiceOrder afterCancel = orderMapper.selectById(Long.valueOf(originalOrderId));
        assertThat(afterCancel.getStatus()).isEqualTo("CANCELLED");
        assertThat(afterCancel.getCancelledAt()).isNotNull();
        assertThat(slotMapper.selectById(targetSlot).getOccupiedCount())
                .as("取消后名额应已释放").isZero();

        // 关键一步：用**原创建请求**重放（同一个 requestId、同一份内容）
        String replayed = bookAndGetOrderId(targetSlot, requestId);
        assertThat(replayed)
                .as("重放必须返回原订单 ID，不得新建第二单")
                .isEqualTo(originalOrderId);

        ServiceOrder afterReplay = orderMapper.selectById(Long.valueOf(originalOrderId));

        // 不复活：状态仍是已取消
        assertThat(afterReplay.getStatus())
                .as("重放创建请求不得复活已取消的订单")
                .isEqualTo("CANCELLED");
        // 不重新占位
        assertThat(slotMapper.selectById(targetSlot).getOccupiedCount())
                .as("重放不得重新占用名额")
                .isZero();
        // 取消时间不被改写
        assertThat(afterReplay.getCancelledAt())
                .as("重放不得改写取消时间")
                .isEqualTo(afterCancel.getCancelledAt());
        // 版本不被推进（没有发生任何状态流转）
        assertThat(afterReplay.getVersion())
                .as("重放不得推进订单版本")
                .isEqualTo(afterCancel.getVersion());

        // CREATE / CANCEL 各 1 条，没有多余的创建日志
        assertThat(orderCount(targetSlot)).isEqualTo(1);
        assertThat(countLogs(targetSlot, "CREATE")).isEqualTo(1);
        assertThat(countLogs(targetSlot, "CANCEL")).isEqualTo(1);
    }

    /**
     * 从未成功的新请求在满额时明确拒绝，不等同于已成功请求的确认重试。
     */
    @Test
    @DisplayName("新键满额拒绝（非B-04/B-05）：从未成功的键两次都明确409，状态稳定")
    void neverSucceedableKeyStaysRejectedWhenFull() throws Exception {
        long oneSlotId = createSlot(1);
        String myRequestId = UUID.randomUUID().toString();

        // 先用另一把键占满名额
        assertThat(bookStatus(oneSlotId, UUID.randomUUID().toString())).isEqualTo(200);

        // 名额已满。此时提交从未成功过的 requestId——
        // 期望是「名额已满」409，而不是凭空返回一个订单。
        int status = bookStatus(oneSlotId, myRequestId);
        assertThat(status).as("从未成功的请求在满额时应明确失败").isEqualTo(409);

        // 再用同一把键重试：结果必须一致，不能因为「这次有名额」就变卦
        assertThat(bookStatus(oneSlotId, myRequestId))
                .as("同一 requestId 的重复提交结果应稳定").isEqualTo(409);

        // 名额仍只有别人的那一笔
        assertThat(slotMapper.selectById(oneSlotId).getOccupiedCount()).isEqualTo(1);
        assertThat(orderCount(oneSlotId)).isEqualTo(1);
        assertThat(countLogs(oneSlotId, "CREATE")).isEqualTo(1);
    }

    /**
     * B-08：同一订单并发取消。
     *
     * <p>期望：最多释放 1 个名额，取消日志只有 1 条，不出现名额变负。
     */
    @Test
    @DisplayName("B-08 同一订单并发取消最多释放一个名额")
    void concurrentCancelReleasesAtMostOnce() throws Exception {
        String orderId = bookAndGetOrderId(UUID.randomUUID().toString());
        assertThat(slotMapper.selectById(slotId).getOccupiedCount()).isEqualTo(1);
        int versionBefore = orderMapper.selectById(Long.valueOf(orderId)).getVersion();

        int attempts = 6;
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            tasks.add(() -> cancelOrder(orderId));
        }

        List<Integer> statuses = runConcurrently(tasks);

        // 当前取消契约：首次成功，其余明确冲突，不能漏过 5xx。
        long ok = statuses.stream().filter(s -> s == 200).count();
        assertThat(ok).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(attempts - 1);

        // 关键：名额只能是 0，不能是负数
        int occupied = slotMapper.selectById(slotId).getOccupiedCount();
        assertThat(occupied).isEqualTo(0);
        assertThat(countLogs("CANCEL")).isEqualTo(1);
        ServiceOrder cancelled = orderMapper.selectById(Long.valueOf(orderId));
        assertThat(cancelled.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getVersion()).as("合法取消应只推进一次版本，不能写回旧版本")
                .isEqualTo(versionBefore + 1);
        assertThat(cancelOrder(orderId)).isEqualTo(409);
        ServiceOrder afterRetry = orderMapper.selectById(Long.valueOf(orderId));
        assertThat(afterRetry.getCancelledAt()).isEqualTo(cancelled.getCancelledAt());
        assertThat(afterRetry.getVersion()).isEqualTo(cancelled.getVersion());
        assertThat(slotMapper.selectById(slotId).getOccupiedCount()).isZero();
        assertThat(countLogs("CANCEL")).isEqualTo(1);
    }

    /** 创建一个未来时段，返回其 ID（由 Mapper 回填主键，不用 LAST_INSERT_ID） */
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
        // Mapper 插入后由 MyBatis-Plus 回填 id，拿到的一定是本次插入的那一行。
        // 不用 SELECT LAST_INSERT_ID()：那依赖「上条 update 与本条 select 同一连接」，
        // 经连接池时并不成立，会拿到别的连接上的值。
        if (slot.getId() == null) {
            throw new IllegalStateException("时段插入后未回填主键");
        }
        return slot.getId();
    }

    // ---------------- 并发工具 ----------------

    /**
     * 并发执行任务。
     *
     * <p>线程池大小取「任务数 + 2」，保证所有任务都能立刻拿到线程起跑，
     * 不会因为线程不足而在 latch 处互相等待造成测试自身死锁。
     */
    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        int threads = tasks.size() + 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);

        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return task.call();
            }));
        }

        startGate.countDown();                       // 统一起跑
        try {
            List<T> results = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            for (Future<T> f : futures) {
                long remain = deadline - System.nanoTime();
                results.add(f.get(Math.max(1, remain), TimeUnit.NANOSECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 发起预约，返回 HTTP 状态码 */
    private int book(String requestId) throws Exception {
        return book(slotId, requestId);
    }

    private int book(Long targetSlotId, String requestId) throws Exception {
        return bookStatus(targetSlotId, requestId);
    }

    /** 发起预约并取出订单 ID，失败时抛异常（用于需要成功结果的场景） */
    private String bookAndGetOrderId(String requestId) throws Exception {
        return bookAndGetOrderId(slotId, requestId);
    }

    private String bookAndGetOrderId(Long targetSlotId, String requestId) throws Exception {
        String body = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(targetSlotId, requestId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("响应中未找到订单ID：" + body);
        }
        return matcher.group(1);
    }

    /** 取消订单，返回 HTTP 状态码；409 视为并发下的正常结果，不抛异常 */
    private int cancelOrder(String orderId) throws Exception {
        return mockMvc.perform(post("/api/service-orders/" + orderId + "/cancel")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withOrderVersion(orderId, """
                                {"reason":"并发取消测试"}
                                """)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String createBody(String requestId) {
        return createBody(slotId, requestId);
    }

    private String createBody(Long targetSlotId, String requestId) {
        return """
                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                 "address":"并发测试地址","requestId":"%s"}
                """.formatted(targetSlotId, requestId);
    }

    /**
     * 发起预约并返回 HTTP 状态码，<b>不因失败而抛异常</b>。
     *
     * <p>并发场景要统计「多少个成功、多少个冲突」，
     * 用 andExpect(status().isOk()) 断言会把冲突当异常抛出，
     * 拿不到计数。取消路径已有类似做法，这里保持一致。
     */
    private int bookStatus(Long targetSlotId, String requestId) throws Exception {
        return performBook(createBody(targetSlotId, requestId));
    }

    /** 发起一次预约，返回 HTTP 状态码（含 4xx/5xx），不抛异常 */
    private int performBook(String body) throws Exception {
        return mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private int orderCount() {
        return orderCount(slotId);
    }

    private int orderCount(Long targetSlotId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_order WHERE slot_id = ?", Integer.class, targetSlotId);
        return n == null ? 0 : n;
    }

    private int countLogs(String action) {
        return countLogs(slotId, action);
    }

    private int countLogs(Long targetSlotId, String action) {
        Integer n = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM service_order_log log
                JOIN service_order o ON o.id = log.order_id
                WHERE o.slot_id = ? AND log.action = ?
                """, Integer.class, targetSlotId, action);
        return n == null ? 0 : n;
    }
}
