package org.imnu.smartcommunitycare.user;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;

import java.time.LocalDateTime;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 为独立浏览器验收建立登记夹具账号，账号使用it_p303v_前缀，验收结束沿用统一夹具清理。
 */
@SpringBootTest
class P303BrowserFixtureAccountTest extends BaseIntegrationTest {

    static final String PREFIX = "it_p303v_";

    @Autowired
    private ServiceSlotMapper slotMapper;

    @Test
    void createRegisteredFixtureAccounts() {
        // 三角色各一个，密码沿用基类常量，与其他测试一致
        createAccount(PREFIX + "admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        createElder(PREFIX + "elder", ELDER_PASSWORD);
        createAccount(PREFIX + "staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);

        // 确认账号真的建出来了：这一步失败说明清理规则把它们误删了
        assertThat(userMapper.findByUsername(PREFIX + "admin"))
                .as("登记夹具：管理员账号应存在").isNotNull();
        assertThat(userMapper.findByUsername(PREFIX + "elder"))
                .as("登记夹具：老人账号应存在").isNotNull();
        assertThat(userMapper.findByUsername(PREFIX + "staff"))
                .as("登记夹具：员工账号应存在").isNotNull();
    }

    /**
     * 登记浏览器验收用的服务与未来时段。
     *
     * <p><b>与账号夹具放在同一个类</b>：两个类各自的 {@code @BeforeEach} 会清理
     * 测试库，先跑完账号类再跑服务类，服务类开头的清理会把刚建的账号删掉。
     * 合并后一次清理、一次建齐，页面验收需要的数据都在。
     */
    @Test
    void createRegisteredFixtureServiceAndSlot() {
        Long serviceId = createFixtureItem(it -> {
            it.setName("浏览器验收服务");
            it.setDescription("用于浏览器验收的服务项目 [IT-FIXTURE]");
            it.setPrice(new java.math.BigDecimal("72.00"));
            it.setProvider("浏览器验收方");
            it.setPhone("13711112222");
        });

        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(serviceId);
        slot.setStartAt(LocalDateTime.now().plusDays(1).withHour(9).withMinute(0));
        slot.setEndAt(LocalDateTime.now().plusDays(1).withHour(12).withMinute(0));
        slot.setCapacity(5);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now());
        slot.setUpdatedAt(LocalDateTime.now());
        slotMapper.insert(slot);
        assertThat(slot.getId()).as("登记夹具：时段应创建并回填主键").isNotNull();
    }
}
