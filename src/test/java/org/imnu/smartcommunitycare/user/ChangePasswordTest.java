package org.imnu.smartcommunitycare.user;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 附件验收表第 77 行：修改密码。
 *
 * <p>四条判据：
 * <ol>
 *   <li>原密码错误 → 明确拒绝；</li>
 *   <li>正确修改后 → <b>旧密码不能登录</b>；</li>
 *   <li>旧会话失效（改密后原令牌立即不可用）；</li>
 *   <li>新密码可以登录。</li>
 * </ol>
 *
 * <p><b>只用登记的测试账号</b>；本类不记录任何密码或令牌到断言消息里。
 * 账号名 {@code it_p303pwd_} 前缀，符合基类 {@code it_} 字面量规则。
 *
 * <p><b>鉴权头用 {@code scc-test-token}</b>：测试 profile 把它配成了
 * {@code sa-token.token-name}，与生产（{@code scc-token}）有意不同。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChangePasswordTest extends BaseIntegrationTest {

    private static final String USER = "it_p303pwd_staff";
    private static final String NEW_PASSWORD = "Pwd@2026New";

    @Autowired
    private MockMvc mockMvc;

    private String token;

    @BeforeEach
    void prepare() throws Exception {
        createAccount(USER, STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        token = loginAndGetToken(mockMvc, USER, STAFF_PASSWORD);
    }

    @Test
    @DisplayName("改密-1 原密码错误被明确拒绝，且密码不变")
    void wrongOldPasswordRejected() throws Exception {
        int code = changePassword("错误的原密码", NEW_PASSWORD);
        assertThat(code).as("原密码错误必须被拒绝").isEqualTo(400);

        // 拒绝后原密码仍可登录——说明确实没改
        assertThat(loginStatus(USER, STAFF_PASSWORD))
                .as("失败的改密不应影响原密码").isEqualTo(200);
    }

    @Test
    @DisplayName("改密-2 新密码与原密码相同时被拒")
    void samePasswordRejected() throws Exception {
        int code = changePassword(STAFF_PASSWORD, STAFF_PASSWORD);
        assertThat(code).as("新旧密码相同应被拒绝").isEqualTo(400);
        assertThat(loginStatus(USER, STAFF_PASSWORD)).isEqualTo(200);
    }

    @Test
    @DisplayName("改密-3 改密后旧密码不能登录、新密码可以登录")
    void oldPasswordStopsWorkingNewOneWorks() throws Exception {
        assertThat(changePassword(STAFF_PASSWORD, NEW_PASSWORD))
                .as("原密码正确时应改密成功").isEqualTo(200);

        assertThat(loginStatus(USER, STAFF_PASSWORD))
                .as("改密后旧密码必须失效").isEqualTo(401);
        assertThat(loginStatus(USER, NEW_PASSWORD))
                .as("改密后新密码必须可用").isEqualTo(200);
    }

    @Test
    @DisplayName("改密-4 旧会话在改密后立即失效")
    void oldSessionInvalidatedAfterChange() throws Exception {
        // 改密前这个令牌是有效的
        mockMvc.perform(put("/api/auth/profile")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"realName":"改密前"}"""))
                .andExpect(status().isOk());

        assertThat(changePassword(STAFF_PASSWORD, NEW_PASSWORD)).isEqualTo(200);

        // 改密后同一令牌必须失效——否则改密失去止损意义
        int code = mockMvc.perform(put("/api/auth/profile")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"realName":"改密后"}"""))
                .andReturn().getResponse().getStatus();
        assertThat(code)
                .as("改密后旧会话应立即失效，攻击者改完密码仍能继续操作就失去意义")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("改密-5 失败时保留非密码资料，不误清已有值")
    void failedChangeKeepsProfileIntact() throws Exception {
        // 先改一次资料
        mockMvc.perform(put("/api/auth/profile")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"realName":"改密前的姓名","phone":"13900001234"}"""))
                .andExpect(status().isOk());

        // 改密失败
        assertThat(changePassword("又错了", NEW_PASSWORD)).isEqualTo(400);

        // 资料应仍在
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/auth/me")
                        .header("scc-test-token", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.realName").value("改密前的姓名"))
                .andExpect(jsonPath("$.data.phone").value("13900001234"));
    }

    @Test
    @DisplayName("改密-6 未登录不能改密")
    void anonymousCannotChangePassword() throws Exception {
        int code = mockMvc.perform(put("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"x","newPassword":"y"}"""))
                .andReturn().getResponse().getStatus();
        assertThat(code).isEqualTo(401);
    }

    // ==================== 辅助 ====================

    /** 改密并返回状态码；口令只出现在本次请求体里，不进任何断言消息 */
    private int changePassword(String oldPw, String newPw) throws Exception {
        return mockMvc.perform(put("/api/auth/password")
                        .header("scc-test-token", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"oldPassword":"%s","newPassword":"%s"}
                                """.formatted(oldPw, newPw)))
                .andReturn().getResponse().getStatus();
    }

    /** 尝试登录并返回状态码；口令不进断言消息 */
    private int loginStatus(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}
                                """.formatted(username, password)))
                .andReturn().getResponse().getStatus();
    }
}
