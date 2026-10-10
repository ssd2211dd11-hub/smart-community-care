package org.imnu.smartcommunitycare.auth;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证与授权的集成测试。
 *
 * <p>用 MockMvc 打真实请求，走完整的过滤器链与拦截器，
 * 不是绕过 Spring 直接调 Service 方法——那样测不出"接口没加校验"这类问题。
 *
 * <p>覆盖 V1.2 §9.2 中与账号相关的验收项：
 * AC-03 三角色与越权、AC-04 会话撤销。
 */
@AutoConfigureMockMvc
class AuthControllerTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("健康检查公开可访问，且真的连着数据库")
    void healthCheckIsPublic() throws Exception {
        mockMvc.perform(get("/api/health-check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("无令牌访问受保护接口返回 401，不是 200 空数据")
    void protectedEndpointRejectsAnonymous() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("正确密码可以登录，返回令牌与角色")
    void loginSucceedsWithCorrectPassword() throws Exception {
        createAccount("it_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_admin","password":"%s"}
                                """.formatted(ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("COMMUNITY_ADMIN"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("passwordHash");
    }

    @Test
    @DisplayName("密码错误返回 401，且不暴露用户名是否存在")
    void loginFailsWithWrongPassword() throws Exception {
        createAccount("it_wrongpwd", ADMIN_PASSWORD, UserRole.ELDER, true);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_wrongpwd","password":"definitely-wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("不存在的用户与错误密码返回同一提示，避免暴露用户是否存在")
    void unknownUserGivesSameMessage() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"no_such_user_at_all","password":"whatever"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("已停用账号无法登录")
    void disabledAccountCannotLogin() throws Exception {
        createAccount("it_disabled", ADMIN_PASSWORD, UserRole.ELDER, false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_disabled","password":"%s"}
                                """.formatted(ADMIN_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("账号已停用，请联系社区管理员"));
    }

    @Test
    @DisplayName("公开注册只能创建老人账号，前端无法伪造角色")
    void publicRegisterAlwaysCreatesElder() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_newelder","password":"Elder@2026",
                                 "realName":"新注册老人","phone":"13900001111",
                                 "role":"COMMUNITY_ADMIN"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        SysUser created = userMapper.findByUsername("it_newelder");
        assertThat(created).isNotNull();
        // 即使请求体里塞了 role，落库必须是 ELDER
        assertThat(created.getRole()).isEqualTo(UserRole.ELDER.name());
    }

    @Test
    @DisplayName("注册后同时建立老人档案，登录即可用")
    void registerAlsoCreatesElderProfile() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_newelder2","password":"Elder@2026",
                                 "realName":"新注册","phone":"13900009999"}
                                """))
                .andExpect(status().isOk());

        // 账号与档案必须同时存在：有账号没档案的老人进不去任何页面
        SysUser user = userMapper.findByUsername("it_newelder2");
        assertThat(user).isNotNull();
        Long profileCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM elder_profile WHERE user_id = ?", Long.class, user.getId());
        assertThat(profileCount).isEqualTo(1L);

        // 登录后能取到自己的档案，而不是 404
        String token = loginAndGetToken(mockMvc, "it_newelder2", "Elder@2026");
        mockMvc.perform(get("/api/health-records")
                        .header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("用户名重复注册返回 409")
    void duplicateUsernameRejected() throws Exception {
        createAccount("it_dup", ELDER_PASSWORD, UserRole.ELDER, true);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_dup","password":"Elder@2026","realName":"重复"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("用户名已被占用"));
    }

    @Test
    @DisplayName("参数校验失败返回 400 与字段级错误")
    void validationErrorReturnsFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ab","password":"123","realName":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
    }

    @Test
    @DisplayName("登录后 /auth/me 返回账号信息，且不含密码哈希")
    void meReturnsCurrentUserWithoutPassword() throws Exception {
        createAccount("it_me", ELDER_PASSWORD, UserRole.ELDER, true);
        String token = loginAndGetToken(mockMvc, "it_me", ELDER_PASSWORD);

        MvcResult result = mockMvc.perform(get("/api/auth/me")
                        .header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("it_me"))
                .andExpect(jsonPath("$.data.role").value("ELDER"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("passwordHash");
        assertThat(body).doesNotContain("$2a$");
    }

    @Test
    @DisplayName("退出后原令牌立即失效")
    void tokenInvalidAfterLogout() throws Exception {
        createAccount("it_logout", ELDER_PASSWORD, UserRole.ELDER, true);
        String token = loginAndGetToken(mockMvc, "it_logout", ELDER_PASSWORD);

        // 退出前可访问
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk());

        // 退出后同一令牌必须被拒
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("会话中的角色被改成未知值后，访问接口被拒而非降级放行")
    void unknownRoleInSessionIsRejected() throws Exception {
        Long userId = createAccount("it_badrole", ELDER_PASSWORD, UserRole.ELDER, true);

        // 先用合法角色登录，拿到可用令牌
        String token = loginAndGetToken(mockMvc, "it_badrole", ELDER_PASSWORD);

        // 登录后再把库里的角色改成未知值，模拟旧库遗留脏数据
        jdbcTemplate.update("UPDATE sys_user SET role = 'ROLE_3' WHERE id = ?", userId);

        // 会话里存的还是旧角色，但后端每次都查库，因此必须识别出角色已非法
        mockMvc.perform(get("/api/auth/me").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("未知角色的账号无法登录")
    void loginFailsForUnknownRole() throws Exception {
        Long userId = createAccount("it_badrole_login", ELDER_PASSWORD, UserRole.ELDER, true);
        jdbcTemplate.update("UPDATE sys_user SET role = 'ROLE_3' WHERE id = ?", userId);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_badrole_login","password":"%s"}
                                """.formatted(ELDER_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("账号角色配置异常，请联系管理员"));
    }
}
