package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 比较同一老人订单接口与相同归属条件的SQL计数。
 * 验证他人记录不泄漏，取消与完成记录仍可查询；不据此推断历史页面差异的原因。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MyOrdersCountTest extends BaseIntegrationTest {

    private static final String ELDER = "it_cnt_elder";
    private static final String OTHER_ELDER = "it_cnt_other";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private ServiceSlotMapper slotMapper;

    private String elderToken;
    private Long serviceId;
    private Long slotId;

    @BeforeEach
    void prepare() throws Exception {
        createElder(ELDER, ELDER_PASSWORD);
        createElder(OTHER_ELDER, ELDER_PASSWORD);
        elderToken = loginAndGetToken(mockMvc, ELDER, ELDER_PASSWORD);

        serviceId = createFixtureItem(it -> {
            it.setName("计数测试服务");
            it.setDescription("用于核对订单计数口径");
            it.setPrice(new BigDecimal("30.00"));
            it.setPhone("13900000000");
        });
        slotId = createSlot(10);
    }

    @Test
    @DisplayName("接口返回条数 = 按该老人档案的 SQL 计数")
    void interfaceCountMatchesSqlCount() throws Exception {
        // 本老人建 3 笔
        for (int i = 0; i < 3; i++) {
            book();
        }
        // 另一位老人在同一时段建 2 笔——这是此前口径易混的关键
        bookAs(OTHER_ELDER);
        bookAs(OTHER_ELDER);

        int bySql = db.queryForObject(
                "SELECT COUNT(*) FROM service_order o"
                        + " JOIN elder_profile ep ON ep.id = o.elder_id"
                        + " JOIN sys_user u ON u.id = ep.user_id"
                        + " WHERE u.username = ?", Integer.class, ELDER);

        int byInterface = fetchMyOrderIds().size();

        assertEquals(3, bySql, "该老人在库里的订单数应为 3");
        assertEquals(bySql, byInterface, "接口返回条数应与该老人的 SQL 计数一致");
    }

    @Test
    @DisplayName("接口不返回其他老人的订单")
    void interfaceExcludesOtherEldersOrders() throws Exception {
        book();
        bookAs(OTHER_ELDER);

        List<Long> mine = fetchMyOrderIds();
        Integer othersTotal = db.queryForObject(
                "SELECT COUNT(*) FROM service_order o"
                        + " JOIN elder_profile ep ON ep.id = o.elder_id"
                        + " JOIN sys_user u ON u.id = ep.user_id"
                        + " WHERE u.username = ?", Integer.class, OTHER_ELDER);

        assertEquals(1, mine.size(), "我只应看到自己的 1 笔");
        assertEquals(1, othersTotal, "对方应有 1 笔");
        for (Long id : mine) {
            Integer belongs = db.queryForObject(
                    "SELECT COUNT(*) FROM service_order o WHERE o.id = ?"
                            + " AND o.elder_id = (SELECT ep.id FROM elder_profile ep"
                            + " JOIN sys_user u ON u.id = ep.user_id WHERE u.username = ?)",
                    Integer.class, id, ELDER);
            assertEquals(1, belongs, "返回的订单 " + id + " 必须属于我");
        }
    }

    @Test
    @DisplayName("订单被取消与完成后仍出现在我的订单列表")
    void cancelledAndCompletedStillListed() throws Exception {
        Long cancelledId = book();
        db.update("UPDATE service_order SET status = 'CANCELLED' WHERE id = ?", cancelledId);
        Long completedId = book();
        db.update("UPDATE service_order SET status = 'COMPLETED' WHERE id = ?", completedId);

        List<Long> mine = fetchMyOrderIds();

        assertEquals(2, mine.size(), "取消与完成都应继续显示，不该从列表消失");
        assertEquals(2, db.queryForObject(
                "SELECT COUNT(*) FROM service_order o"
                        + " JOIN elder_profile ep ON ep.id = o.elder_id"
                        + " JOIN sys_user u ON u.id = ep.user_id WHERE u.username = ?",
                Integer.class, ELDER).intValue());
    }

    // ==================== 辅助 ====================

    private Long book() throws Exception {
        return bookAs(ELDER);
    }

    private Long bookAs(String username) throws Exception {
        String payload = """
                {"slotId":%d,"contactName":"张建国","contactPhone":"13900000003",
                 "address":"计数测试地址","requestId":"%s"}
                """.formatted(slotId, UUID.randomUUID().toString());
        String token = loginAndGetToken(mockMvc, username, ELDER_PASSWORD);
        String resp = mockMvc.perform(post("/api/service-orders")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
        if (!m.find()) {
            throw new IllegalStateException("建单响应里应含订单 ID：" + resp);
        }
        return Long.valueOf(m.group(1));
    }

    /** 调「我的订单」并取回其中的订单 ID */
    private List<Long> fetchMyOrderIds() throws Exception {
        String resp = mockMvc.perform(get("/api/service-orders")
                        .header("scc-test-token", "Bearer " + elderToken))
                .andReturn().getResponse().getContentAsString();
        var m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)\"?").matcher(resp);
        List<Long> ids = new java.util.ArrayList<>();
        while (m.find()) {
            ids.add(Long.valueOf(m.group(1)));
        }
        return ids;
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