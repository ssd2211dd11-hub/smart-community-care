package org.imnu.smartcommunitycare.auth;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 为独立浏览器验收在专用测试库建立登记账号。
 * 公开注册仍只允许老人，不修改注册角色权限；该用例仅准备账号，页面行为另行验证。
 */
class P303FixtureAccountTest extends BaseIntegrationTest {

    /** 与页面验收脚本约定的账号名前缀 */
    static final String PREFIX = "it_p303_";

    @Test
    void createRegisteredFixtureAccountsForBrowserReview() {
        // 管理员：验收服务上下架与订单管理
        Long adminId = createAccountIfAbsent(PREFIX + "admin", ADMIN_PASSWORD,
                UserRole.COMMUNITY_ADMIN, true);

        // 老人：验收我的订单列表与详情
        createElderIfAbsent(PREFIX + "elder", ELDER_PASSWORD);

        // 工作人员：验收本人任务列表与详情
        createAccountIfAbsent(PREFIX + "staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);

        assertThat(adminId).as("管理员夹具账号应建立成功").isNotNull();
    }

    private Long createAccountIfAbsent(String username, String password,
                                       UserRole role, boolean enabled) {
        if (userMapper.findByUsername(username) != null) {
            return userMapper.findByUsername(username).getId();
        }
        return createAccount(username, password, role, enabled);
    }

    private void createElderIfAbsent(String username, String password) {
        if (elderProfileMapper != null && userMapper.findByUsername(username) != null) {
            return;
        }
        createElder(username, password);
    }
}
