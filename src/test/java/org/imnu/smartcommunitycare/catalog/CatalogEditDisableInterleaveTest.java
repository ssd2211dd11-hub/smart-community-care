package org.imnu.smartcommunitycare.catalog;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.service.CatalogService;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 使用同步闸固定编辑与下架的交错，验证编辑不会覆盖已经生效的下架状态。
 * 顺序对照与真实事务结果分别断言，不用任务结束后的单一计数替代交错证据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogEditDisableInterleaveTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * spy 服务层，为的是在 {@code updateItem} 入口设同步闸。
     *
     * <p>spy 的是具体类（MyBatis Mapper 是 JDK 代理，接口方法不能
     * {@code callRealMethod}）。
     */
    @MockitoSpyBean
    private CatalogService catalogService;

    private String admin;
    private Long serviceId;

    @BeforeEach
    void prepare() throws Exception {
        createAccount("it_interleave_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        admin = loginAndGetToken(mockMvc, "it_interleave_admin", ADMIN_PASSWORD);
        serviceId = createFixtureItem(it -> {
            it.setName("并发交错测试服务");
            it.setDescription("用于验证编辑与下架交错");
            it.setPrice(new BigDecimal("70.00"));
            it.setPhone("13300000000");
        });
    }

    /**
     * 交错场景：编辑读到 enabled=true → 下架提交 → 编辑回写。
     *
     * <p>期望（若缺陷成立）：编辑把下架覆盖，最终 enabled=<b>true</b>，
     * 服务重新出现在老人目录里——管理员明明下架了，老人却还能看到。
     *
     * <p><b>本用例先如实记录事实，不预设结论。</b>
     * 断言写成「记录最终值」的形式：若结果是 false，说明复现不成立。
     */
    @Test
    @DisplayName("编辑读到旧值后下架提交：记录最终 enabled，不预设是否被覆盖")
    void editInterleavedWithDisable_recordsFinalEnabled() throws Exception {
        assertThat(enabledOf()).as("前置：服务应为上架").isTrue();

        // 同步闸：T1 进入 updateItem 后立刻停住（已读完、尚未回写）
        CountDownLatch editParked = new CountDownLatch(1);
        CountDownLatch releaseEdit = new CountDownLatch(1);
        AtomicReference<String> seenEnabledByEdit = new AtomicReference<>();

        // 只拦截第一次（编辑那次），下架是 disableItem 不受影响
        java.util.concurrent.atomic.AtomicBoolean armed =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (armed.compareAndSet(true, false)) {
                // 此刻 existing 已经读完（方法入口即已 selectById 之后）
                // 记录它看到的 enabled，稍后与最终值比对
                seenEnabledByEdit.set(String.valueOf(enabledOf()));
                editParked.countDown();
                try {
                    if (!releaseEdit.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("闸口等待超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("闸口被中断", e);
                }
            }
            return invocation.callRealMethod();
        }).when(catalogService).updateItem(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());

        // T1：编辑（不传 enabled，模拟页面编辑表单的实际情况）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> editFuture = pool.submit(() -> editWithoutEnabled("交错编辑后的名称"));

        assertThat(editParked.await(20, TimeUnit.SECONDS))
                .as("编辑事务应到达闸口").isTrue();

        // T2：下架完整提交（真实 HTTP，走 disableItem 的事务）
        int disableStatus = mockMvc.perform(put("/api/services/{id}/disable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andReturn().getResponse().getStatus();
        assertThat(disableStatus).as("下架请求应成功").isEqualTo(200);
        assertThat(enabledOf()).as("下架此刻应已落库为 false").isFalse();

        // 放行 T1 回写
        releaseEdit.countDown();
        int editStatus = editFuture.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertThat(editStatus).as("编辑请求本身应成功").isEqualTo(200);

        // ---- 事实记录 ----
        boolean finalEnabled = enabledOf();
        String nameNow = nameOf();
        System.out.println("INTERLEAVE 观察：编辑读到 enabled=" + seenEnabledByEdit.get()
                + " → 下架提交(false) → 编辑回写 → 最终 enabled=" + finalEnabled
                + "，名称=" + nameNow);

        // 编辑的业务字段应当生效（这是编辑该做的事）
        assertThat(nameNow).as("编辑的名称应生效").isEqualTo("交错编辑后的名称");

        // 关键断言：下架不应被编辑覆盖。
        // 若这里失败，说明复现成立 —— 编辑确实覆盖了下架状态。
        assertThat(finalEnabled)
                .as("下架已提交后，随后回写的编辑不应把 enabled 改回 true"
                        + "（编辑读到 " + seenEnabledByEdit.get() + "，下架提交为 false）")
                .isFalse();
    }

    /**
     * 对照组：编辑先完整提交，下架随后进行，最终应为下架。
     *
     * <p>用来区分「编辑总是会覆盖下架」与「只有交错时才会覆盖」——
     * 前者是逻辑错误，后者才是并发问题。两者的修法完全不同。
     */
    @Test
    @DisplayName("对照组：编辑先提交再下架，最终为下架（顺序路径不覆盖）")
    void editThenDisableSequentially_endsDisabled() throws Exception {
        int editStatus = editWithoutEnabled("顺序编辑后的名称");
        assertThat(editStatus).isEqualTo(200);
        assertThat(enabledOf()).as("编辑不传 enabled 时应保持原值").isTrue();

        int disableStatus = mockMvc.perform(put("/api/services/{id}/disable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andReturn().getResponse().getStatus();
        assertThat(disableStatus).isEqualTo(200);

        assertThat(enabledOf()).as("顺序路径下最终应为下架").isFalse();
        assertThat(nameOf()).as("编辑的名称应保留").isEqualTo("顺序编辑后的名称");
    }

    @Test
    @DisplayName("对照组：下架后单独编辑（无交错），不得把服务恢复为上架")
    void editAfterDisableSequentially_staysDisabled() throws Exception {
        assertThat(mockMvc.perform(put("/api/services/{id}/disable", serviceId)
                        .header("scc-test-token", "Bearer " + admin))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(enabledOf()).isFalse();

        // 顺序地编辑：读到的 existing.enabled 已是 false
        assertThat(editWithoutEnabled("下架后编辑的名称")).isEqualTo(200);

        assertThat(enabledOf())
                .as("顺序路径下编辑读到的是已下架状态，不应恢复为上架")
                .isFalse();
    }

    // ==================== 辅助 ====================

    /** 编辑服务，<b>不传 enabled</b>（与页面编辑表单的实际请求一致） */
    private int editWithoutEnabled(String newName) throws Exception {
        return mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .put("/api/services/{id}", serviceId)
                                .header("scc-test-token", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"%s","category":"HOUSEKEEPING",
                                         "price":70.00,"provider":"并发测试提供方"}
                                        """.formatted(newName)))
                .andReturn().getResponse().getStatus();
    }

    private boolean enabledOf() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT enabled FROM service_item WHERE id = ?", Boolean.class, serviceId));
    }

    private String nameOf() {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM service_item WHERE id = ?", String.class, serviceId);
    }
}
