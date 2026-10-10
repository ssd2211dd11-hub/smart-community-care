package org.imnu.smartcommunitycare.user;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账号维护与角色隔离的集成测试。
 *
 * <p>补P1 收尾要求的场景：密码变更、停用后旧令牌、角色隔离。
 * 对象归属与账号档案事务需要老人档案，属P2范围，此处不重复实现。
 */
@AutoConfigureMockMvc
class UserAccountTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // ---------- 密码变更 ----------

    @Test
    @DisplayName("修改密码后旧令牌立即失效，需用新密码重新登录")
    void passwordChangeRevokesOldToken() throws Exception {
        createAccount("it_pwd", ELDER_PASSWORD, UserRole.ELDER, true);
        String token = loginAndGetToken(mockMvc, "it_pwd", ELDER_PASSWORD);

        // 改密前可访问
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/auth/password")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"%s","newPassword":"NewPass@2026"}
                                """.formatted(ELDER_PASSWORD)))
                .andExpect(status().isOk());

        // 旧令牌必须失效
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        // 新密码可登录
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_pwd","password":"NewPass@2026"}
                                """))
                .andExpect(status().isOk());

        // 旧密码不能再登录
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_pwd","password":"%s"}
                                """.formatted(ELDER_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("原密码错误时拒绝改密")
    void passwordChangeRequiresCorrectOldPassword() throws Exception {
        createAccount("it_pwd_old", ELDER_PASSWORD, UserRole.ELDER, true);
        String token = loginAndGetToken(mockMvc, "it_pwd_old", ELDER_PASSWORD);

        mockMvc.perform(put("/api/auth/password")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"wrong-old","newPassword":"NewPass@2026"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("原密码不正确"));
    }

    @Test
    @DisplayName("未登录不能改密")
    void passwordChangeRequiresLogin() throws Exception {
        mockMvc.perform(put("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"a","newPassword":"NewPass@2026"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 停用账号 ----------

    @Test
    @DisplayName("停用账号后其旧令牌立即失效")
    void disabledAccountTokenIsRevoked() throws Exception {
        Long staffId = createAccount("it_staff_tok", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_admin_tok", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);

        String staffToken = loginAndGetToken(mockMvc, "it_staff_tok", STAFF_PASSWORD);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_tok", ADMIN_PASSWORD);

        // 停用前员工令牌可用
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + staffToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/users/{id}/disable", staffId)
                        .header("scc-test-token", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 停用后旧令牌必须失效
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + staffToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("停用后该账号无法再登录")
    void disabledAccountCannotLogin() throws Exception {
        Long staffId = createAccount("it_staff_noLogin", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_admin_noLogin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_noLogin", ADMIN_PASSWORD);

        mockMvc.perform(put("/api/users/{id}/disable", staffId)
                        .header("scc-test-token", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_staff_noLogin","password":"%s"}
                                """.formatted(STAFF_PASSWORD)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("不能停用最后一个启用的管理员")
    void cannotDisableLastEnabledAdmin() throws Exception {
        // 本用例需要「只剩一个启用管理员」的前提。
        //
        // 原先写的是 DELETE FROM sys_user WHERE role = 'COMMUNITY_ADMIN'——
        // 不区分夹具，会把测试库里所有管理员连同非夹具的一起删掉。
        // 与本轮全部修正的方向相反，这里一并收窄为只删夹具。
        //
        // 同时校验库名：这是本类里唯一直接执行 DELETE 的地方，
        // 配置写错库时，它会是第一个破坏数据的语句。
        assertDatabaseIs(TEST_DATABASE);
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE ?", FIXTURE_SQL_PREFIX);
        Long adminId = createAccount("it_only_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String token = loginAndGetToken(mockMvc, "it_only_admin", ADMIN_PASSWORD);

        mockMvc.perform(put("/api/users/{id}/disable", adminId)
                        .header("scc-test-token", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("不能停用最后一个启用的社区管理员"));

        // 拒绝后账号仍启用，会话也不该被踢
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ---------- 角色隔离 ----------

    @Test
    @DisplayName("老人不能停用账号（角色不允许，应403）")
    void elderCannotDisableAccount() throws Exception {
        Long target = createAccount("it_target", ELDER_PASSWORD, UserRole.ELDER, true);
        String elderToken = loginAndGetToken(mockMvc, "it_target", ELDER_PASSWORD);

        mockMvc.perform(put("/api/users/{id}/disable", target)
                        .header("scc-test-token", "Bearer " + elderToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("服务人员不能停用账号（角色不允许，应403）")
    void staffCannotDisableAccount() throws Exception {
        Long target = createAccount("it_staff_target", ELDER_PASSWORD, UserRole.ELDER, true);
        createAccount("it_staff_actor", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        String staffToken = loginAndGetToken(mockMvc, "it_staff_actor", STAFF_PASSWORD);

        mockMvc.perform(put("/api/users/{id}/disable", target)
                        .header("scc-test-token", "Bearer " + staffToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("管理员可以停用服务人员")
    void adminCanDisableStaff() throws Exception {
        Long staffId = createAccount("it_staff_ok", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_admin_ok", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_ok", ADMIN_PASSWORD);

        mockMvc.perform(put("/api/users/{id}/disable", staffId)
                        .header("scc-test-token", "Bearer " + adminToken))
                .andExpect(status().isOk());

        SysUser after = userMapper.selectById(staffId);
        assertThat(after).isNotNull();
        assertThat(after.getEnabled()).isFalse();
    }

    @Test
    @DisplayName("停用不物理删除账号，历史记录保留")
    void disableKeepsAccountRecord() throws Exception {
        Long staffId = createAccount("it_staff_keep", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_admin_keep", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_keep", ADMIN_PASSWORD);

        mockMvc.perform(put("/api/users/{id}/disable", staffId)
                        .header("scc-test-token", "Bearer " + adminToken))
                .andExpect(status().isOk());

        Long remaining = userMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getId, staffId));
        assertThat(remaining).isEqualTo(1L);
    }

    @Test
    @DisplayName("停用不存在的账号返回404")
    void disableNonExistentAccountReturns404() throws Exception {
        createAccount("it_admin_404", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String adminToken = loginAndGetToken(mockMvc, "it_admin_404", ADMIN_PASSWORD);

        mockMvc.perform(put("/api/users/{id}/disable", 999999)
                        .header("scc-test-token", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }
}
