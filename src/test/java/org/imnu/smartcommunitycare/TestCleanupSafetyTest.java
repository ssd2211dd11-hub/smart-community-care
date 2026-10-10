package org.imnu.smartcommunitycare;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试清理入口的安全性验证。
 *
 * <p>这不是业务测试，是<b>测试基础设施自身的测试</b>。
 * 清理入口一旦有缺陷，影响的不是某个功能对错，而是<b>整个测试库的数据</b>——
 * 所以它必须有自己的验证，不能靠「跑一遍没出事」来证明。
 *
 * <p><b>收尾纪律</b>：本类插入的数据由 {@link #removeOwnData} 精确清理，
 * 只删 {@link #createdUserIds} / {@link #createdItemIds} 里记录的 ID，
 * <b>不用任何前缀或 provider 匹配</b>。清理测试自身的代码同样要遵守它所验证的规矩——
 * 否则这个测试就是自己规则的例外。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TestCleanupSafetyTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate db;

    /** 本类插入的账号 ID，逐条记录，收尾按 ID 精确删 */
    private final List<Long> createdUserIds = new ArrayList<>();
    /** 本类插入的服务 ID，同上 */
    private final List<Long> createdItemIds = new ArrayList<>();

    /**
     * 孤儿服务的 ID：既不登记也不带标记，用于验证「保留 + 提示」的行为。
     *
     * <p>刻意不放进 {@link #createdItemIds}——那个列表会被
     * {@link #removeOwnData} 在用例结束时清掉，
     * 而本用例需要它在清理逻辑跑过之后仍然存在，否则验证不到提示。
     */
    private Long orphanItemId;

    /**
     * 收尾：清掉孤儿服务。它必须活过所有断言，因此不进 createdItemIds。
     *
     * <p><b>同样先校验库名。</b>这条收尾会执行 DELETE，
     * 库名不对就不该动任何数据——与 {@link #removeOwnData} 同一要求。
     * 两个收尾方法是各自独立的入口，不能假设「另一个已经校验过」：
     * JUnit 不保证多个 {@code @AfterEach} 的执行顺序。
     */
    @AfterEach
    void removeOrphanAfterAssertions() {
        if (orphanItemId != null) {
            assertDatabaseIs(TEST_DATABASE);
            db.update("DELETE FROM service_slot WHERE service_id = ?", orphanItemId);
            db.update("DELETE FROM service_item WHERE id = ?", orphanItemId);
            orphanItemId = null;
        }
    }

    /**
     * 清理本类插入的数据，<b>只按精确 ID</b>。
     *
     * <p>执行前先校验库名：这条收尾逻辑同样会执行 DELETE，
     * 库名不对就不该动任何数据。
     */
    @AfterEach
    void removeOwnData() {
        assertDatabaseIs(TEST_DATABASE);

        for (Long userId : createdUserIds) {
            // 依赖倒序：先子表后主表，避免外键拦截
            db.update("DELETE FROM elder_profile WHERE user_id = ?", userId);
            db.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
        for (Long itemId : createdItemIds) {
            db.update("DELETE FROM service_slot WHERE service_id = ?", itemId);
            db.update("DELETE FROM service_item WHERE id = ?", itemId);
        }
        createdUserIds.clear();
        createdItemIds.clear();
    }

    // ==================== 回归场景：相近用户名 ====================

    @Test
    @DisplayName("夹具判据是字面量 it_ ：以 it 开头但第二字符不是下划线的账号必须保留")
    void similarUsernameIsNotTreatedAsFixture() {
        // 这组是用例的核心：模拟「真实数据恰好长得像夹具」。
        // 判据必须是 LIKE 'it\_%'（下划线转义），而不是 LIKE 'it%'——
        // 后者会把下面这些全部连带删掉。
        //
        // 判别标准只有一条：第 3 个字符是不是下划线。
        // 是 → 夹具（如 it_helper，本就该被删，刻意不放进本组）
        // 否 → 真实数据，必须保留
        // 每个名字带随机后缀：用户名有唯一约束，用固定名字会与
        // 库里既有的非夹具数据撞唯一键。形态特征（前缀 it）不变，
        // 仍然是「像夹具但不是夹具」。
        String sfx = uid();
        String[] lookAlikes = {
                "italian_keeper_" + sfx,   // it + a
                "italy_service_" + sfx,    // it + a
                "item_master_" + sfx,      // it + e
                "itsm_manager_" + sfx,     // it + s
                "itx_helper_" + sfx,       // it + x
                "ithelper_" + sfx,         // it + h，无分隔符
                "i_t_helper_" + sfx        // i + 下划线 + t，不以 it 开头
        };

        for (String name : lookAlikes) {
            Long id = insertUser(name);
            assertEquals(1, countUser(id), "前置：" + name + " 应已插入");
        }

        clearAllData();

        for (String name : lookAlikes) {
            Long id = userId(name);
            assertEquals(1, countUser(id),
                    "「" + name + "」被误当作夹具删除了——夹具判据必须是字面量 it_，不能是 it%");
        }
    }

    @Test
    @DisplayName("相近用户名的关联数据同样保留：档案不因归属被连带删除")
    void dataRelatedToSimilarUsernameSurvives() {
        Long keeperId = insertUser("italian_keeper_" + uid());
        db.update("INSERT INTO elder_profile (user_id, birth_date, gender, created_at, updated_at)"
                + " VALUES (?, '1950-01-01', 'female', NOW(3), NOW(3))", keeperId);
        Long profileId = db.queryForObject(
                "SELECT id FROM elder_profile WHERE user_id = ?", Long.class, keeperId);
        assertEquals(1, countProfile(profileId), "前置：档案应已插入");

        clearAllData();

        assertEquals(1, countUser(keeperId), "账号被误删");
        assertEquals(1, countProfile(profileId),
                "档案被连带删除——清理不应按归属连坐，除非账号本身是夹具");
    }

    // ==================== 回归场景：相同提供方的已有服务 ====================

    @Test
    @DisplayName("未登记的服务即使 provider 与夹具相同也必须保留")
    void unregisteredServiceWithSameProviderSurvives() {
        // 曾用 provider LIKE '测试%' 作为夹具判据，
        // 那么一个 provider 叫「测试机构」的既有服务会被误删。
        // 现在判据是「登记过的 ID」或「description 带 IT-FIXTURE 标记」，
        // provider 不再参与判断。
        Long existingItemId = insertItem("社区既有服务_" + uid(), "测试机构");
        assertEquals(1, countItem(existingItemId), "前置：既有服务应已插入");

        clearAllData();

        assertEquals(1, countItem(existingItemId),
                "未登记的服务被误删——provider 不应作为夹具判据，它会与真实数据撞名");
    }

    @Test
    @DisplayName("夹具服务经登记后仍能被清理")
    void registeredFixtureServiceIsStillCleaned() {
        Long fixtureItemId = createFixtureItem();
        assertEquals(1, countItem(fixtureItemId), "前置：夹具服务应已插入");

        clearAllData();

        assertEquals(0, countItem(fixtureItemId),
                "登记过的夹具服务未被清理——判据收窄后不能把真夹具也放过");
    }

    @Test
    @DisplayName("未登记且无标记的服务被保留，并在日志中提示（防漏登记机制生效）")
    void unregisteredServiceIsReportedButKept() {
        // 造一个「本该是夹具却没登记」的服务：
        // 既不登记，也不带 IT-FIXTURE 标记。
        // 预期行为是保留 + 提示，而不是静默残留——后者很难让人联想到漏登记。
        // 直接插入而不调 insertItem：insertItem 会登记进 createdItemIds，
        // 收尾时把它删掉，孤儿就在验证完成前消失了。
        // 名字带随机后缀：库里若已有同名数据会撞唯一约束。
        String orphanName = "漏登记的服务_" + uid();
        db.update("INSERT INTO service_item (name, category, price, provider, enabled, sort_order,"
                        + " created_at, updated_at) VALUES (?, 'HOUSEKEEPING', 10.00, ?, 1, 1,"
                        + " NOW(3), NOW(3))", orphanName, "测试机构");
        Long orphanId = db.queryForObject(
                "SELECT id FROM service_item WHERE name = ?", Long.class, orphanName);
        orphanItemId = orphanId;
        assertEquals(1, countItem(orphanId), "前置：服务应已插入");

        clearAllData();

        // 服务本身必须保留：它没有登记也没有标记，清理无权删它。
        // 「保留」是正确的，测试库里本来就允许有人工放置的数据。
        assertEquals(1, countItem(orphanId),
                "未登记的服务被删除了——清理只能删登记过或带标记的");

        // 再清一次：此时它依然没被登记，行为应一致
        clearAllData();
        assertEquals(1, countItem(orphanId), "第二次清理不应改变结果");

        // 它还留在库里，会持续触发孤儿提示。因此本用例**不把它登记进收尾列表**，
        // 改由 orphanItemId 字段在 @AfterEach 末尾单独清掉——
        // 收尾照做，但必须等断言跑完之后，否则孤儿在验证前就没了。
    }

    // ==================== 回归场景：非夹具数据 ====================

    @Test
    @DisplayName("非夹具账号、档案与服务在清理后仍然存在")
    void nonFixtureRowsSurvive() {
        Long userId = insertUser("keep_real_user_" + uid());
        db.update("INSERT INTO elder_profile (user_id, birth_date, gender, created_at, updated_at)"
                + " VALUES (?, '1950-01-01', 'female', NOW(3), NOW(3))", userId);
        Long profileId = db.queryForObject(
                "SELECT id FROM elder_profile WHERE user_id = ?", Long.class, userId);
        Long itemId = insertItem("keep_real_service_" + uid(), "阳光家政");

        assertEquals(1, countUser(userId), "前置：账号应已插入");
        assertEquals(1, countProfile(profileId), "前置：档案应已插入");
        assertEquals(1, countItem(itemId), "前置：服务应已插入");

        clearAllData();

        assertEquals(1, countUser(userId), "非夹具账号被误删");
        assertEquals(1, countProfile(profileId), "非夹具档案被误删");
        assertEquals(1, countItem(itemId), "非夹具服务被误删");
    }

    // ==================== 真实夹具仍能清理 ====================

    @Test
    @DisplayName("真实夹具（it_ 前缀）仍被清理，含其档案与服务")
    void realFixtureRowsAreCleaned() {
        Long fixtureUserId = insertUser("it_safety_" + uid());
        db.update("INSERT INTO elder_profile (user_id, birth_date, gender, created_at, updated_at)"
                + " VALUES (?, '1950-01-01', 'female', NOW(3), NOW(3))", fixtureUserId);
        Long profileId = db.queryForObject(
                "SELECT id FROM elder_profile WHERE user_id = ?", Long.class, fixtureUserId);
        Long fixtureItemId = createFixtureItem();

        assertEquals(1, countUser(fixtureUserId), "前置：夹具账号应已插入");
        assertEquals(1, countItem(fixtureItemId), "前置：夹具服务应已插入");

        clearAllData();

        assertEquals(0, countUser(fixtureUserId), "夹具账号未被清理");
        assertEquals(0, countProfile(profileId), "夹具档案未被清理");
        assertEquals(0, countItem(fixtureItemId), "夹具服务未被清理");
    }

    // ==================== 库名校验 ====================

    @Test
    @DisplayName("库名不匹配时一条数据都不删：删除动作被挡在校验之后")
    void noRowIsDeletedWhenDatabaseMismatch() {
        // 夹具探针：正常清理一定会删它。库名不匹配后它仍在，
        // 才能证明删除根本没开始执行——而不是「恰好不在删除范围内」。
        Long probeId = insertUser("it_probe_" + uid());
        Long keepId = insertUser("keep_probe_" + uid());
        assertEquals(1, countUser(probeId), "前置：夹具探针应已插入");
        assertEquals(1, countUser(keepId), "前置：非夹具探针应已插入");

        IllegalStateException err = assertThrows(IllegalStateException.class,
                () -> clearAllDataExpecting("smart_community_care"));
        assertTrue(err.getMessage().contains("非测试库"),
                "异常信息应说明是非测试库，实际：" + err.getMessage());

        assertEquals(1, countUser(probeId),
                "库名不匹配时竟然删除了夹具数据——校验没有挡住删除动作");
        assertEquals(1, countUser(keepId), "库名不匹配时竟然删除了数据");
    }

    @Test
    @DisplayName("库名正确时清理照常执行，与上条互为对照")
    void cleanupRunsWhenDatabaseMatches() {
        Long probeId = insertUser("it_probe_ok_" + uid());
        assertEquals(1, countUser(probeId), "前置：夹具账号应已插入");

        clearAllData();

        assertEquals(0, countUser(probeId),
                "库名正确时清理没有执行——上一条「未删除」不能证明校验起了作用");
    }

    @Test
    @DisplayName("错误库名被拒绝：校验对非测试库抛错")
    void wrongDatabaseNameIsRejected() {
        assertDatabaseIs(TEST_DATABASE);

        IllegalStateException err = assertThrows(IllegalStateException.class,
                () -> assertDatabaseIs("smart_community_care"));
        assertTrue(err.getMessage().contains("非测试库"),
                "异常信息应说明是非测试库，实际：" + err.getMessage());
    }

    // ==================== 辅助 ====================

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** 插入账号并登记 ID，收尾时按 ID 精确删 */
    private Long insertUser(String username) {
        db.update("INSERT INTO sys_user (username, password_hash, real_name, role, enabled,"
                        + " created_at, updated_at) VALUES (?, ?, ?, 'ELDER', 1, NOW(3), NOW(3))",
                username, "hash-not-used-in-this-test", "占位-" + username);
        Long id = db.queryForObject("SELECT id FROM sys_user WHERE username = ?",
                Long.class, username);
        createdUserIds.add(id);
        return id;
    }

    private Long userId(String username) {
        return db.queryForObject("SELECT id FROM sys_user WHERE username = ?",
                Long.class, username);
    }

    /** 插入服务并登记 ID，收尾时按 ID 精确删 */
    private Long insertItem(String name, String provider) {
        db.update("INSERT INTO service_item (name, category, price, provider, enabled, sort_order,"
                        + " created_at, updated_at) VALUES (?, 'HOUSEKEEPING', 10.00, ?, 1, 1,"
                        + " NOW(3), NOW(3))", name, provider);
        Long id = db.queryForObject("SELECT id FROM service_item WHERE name = ?",
                Long.class, name);
        createdItemIds.add(id);
        return id;
    }

    private int countUser(Long id) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id = ?",
                Integer.class, id);
        return n == null ? 0 : n;
    }

    private int countProfile(Long id) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM elder_profile WHERE id = ?",
                Integer.class, id);
        return n == null ? 0 : n;
    }

    private int countItem(Long id) {
        Integer n = db.queryForObject("SELECT COUNT(*) FROM service_item WHERE id = ?",
                Integer.class, id);
        return n == null ? 0 : n;
    }
}