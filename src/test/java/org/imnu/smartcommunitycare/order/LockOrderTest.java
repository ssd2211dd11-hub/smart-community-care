package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态检查服务与时段的锁请求顺序。
 * 源码顺序不能证明所有运行路径绝无死锁，真实锁等待另由事务竞争测试验证。
 */
class LockOrderTest extends BaseIntegrationTest {

    private static final Pattern ITEM_LOCK =
            Pattern.compile("itemMapper\\.findByIdForUpdate");
    private static final Pattern SLOT_LOCK =
            Pattern.compile("slotMapper\\.findByIdForUpdate");

    private static final Path ORDER_CREATOR = Path.of(
            "src/main/java/org/imnu/smartcommunitycare/order/service/OrderCreator.java");
    private static final Path CATALOG_SERVICE = Path.of(
            "src/main/java/org/imnu/smartcommunitycare/catalog/service/CatalogService.java");

    @Test
    @DisplayName("建单路径按「服务 → 时段」加锁")
    void orderCreatorLocksServiceBeforeSlot() throws IOException {
        String src = read(ORDER_CREATOR);
        int serviceAt = ITEM_LOCK.matcher(src).find()
                ? src.indexOf("itemMapper.findByIdForUpdate") : -1;
        int slotAt = SLOT_LOCK.matcher(src).find()
                ? src.indexOf("slotMapper.findByIdForUpdate") : -1;

        assertTrue(serviceAt >= 0, "建单路径应锁服务行");
        assertTrue(slotAt >= 0, "建单路径应锁时段行");
        assertTrue(serviceAt < slotAt,
                "建单路径必须先锁服务行再锁时段行。实际位置：服务@%d，时段@%d。"
                        .formatted(serviceAt, slotAt)
                        + "顺序颠倒会在「下架服务」与「建单」并发时产生环。");
    }

    @Test
    @DisplayName("lockSlot（关时段 / 调容量）按「服务 → 时段」加锁")
    void lockSlotLocksServiceBeforeSlot() throws IOException {
        String src = read(CATALOG_SERVICE);
        // lockSlot 方法体范围：从方法签名到下一个方法定义
        int start = src.indexOf("private ServiceSlot lockSlot");
        assertTrue(start > 0, "应能找到 lockSlot 方法");
        int end = src.indexOf("\n    private ", start + 10);
        String body = end > 0 ? src.substring(start, end) : src.substring(start);

        int serviceAt = body.indexOf("itemMapper.findByIdForUpdate");
        int slotAt = body.indexOf("slotMapper.findByIdForUpdate");

        assertTrue(serviceAt >= 0, "lockSlot 应锁服务行");
        assertTrue(slotAt >= 0, "lockSlot 应锁时段行");
        assertTrue(serviceAt < slotAt,
                "lockSlot 必须先锁服务行再锁时段行。实际位置：服务@%d，时段@%d"
                        .formatted(serviceAt, slotAt));
    }

    @Test
    @DisplayName("下架服务在改状态前先锁服务行")
    void disableItemLocksServiceBeforeUpdate() throws IOException {
        String src = read(CATALOG_SERVICE);
        int start = src.indexOf("public void disableItem");
        assertTrue(start > 0, "应能找到 disableItem 方法");
        int end = src.indexOf("\n    private ", start + 10);
        String body = end > 0 ? src.substring(start, end) : src.substring(start);

        int lockAt = body.indexOf("itemMapper.findByIdForUpdate");
        int updateAt = body.indexOf("itemMapper.updateById");

        assertTrue(lockAt >= 0,
                "disableItem 必须在改状态前锁服务行——"
                        + "只靠 UPDATE 自身的锁，其生效时刻晚于并发建单的读");
        assertTrue(updateAt > lockAt,
                "锁服务行必须在 updateById 之前。实际位置：锁@%d，更新@%d"
                        .formatted(lockAt, updateAt));
    }

    private String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "源码文件不存在：" + p.toAbsolutePath()
                + "（测试需在模块根目录执行）");
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}