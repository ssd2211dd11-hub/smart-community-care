package org.imnu.smartcommunitycare.auth;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 错误响应语义测试。
 *
 * <p>覆盖"接口不存在"与"对象不存在"两类 404：它们的区别在于
 * <ul>
 *   <li>路由不存在 → 404，调用方能判断是自己URL 写错了</li>
 *   <li>对象不在可见范围 → 404，<b>不泄露对象是否存在</b></li>
 * </ul>
 *
 * <p>回归背景：Spring Boot 4 对未匹配路由抛 {@code NoResourceFoundException}，
 * 不单独处理时会落进兜底分支返回 500，把"接口没写"误报成"服务器故障"。
 */
@AutoConfigureMockMvc
class NotFoundHandlingTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("访问不存在的接口返回 404，不是 500")
    void unknownEndpointReturns404() throws Exception {
        createAccount("it_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String token = loginAndGetToken(mockMvc, "it_admin", ADMIN_PASSWORD);
        mockMvc.perform(get("/api/definitely-not-a-real-endpoint")
                        .header("scc-test-token", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("接口不存在"));
    }

    @Test
    @DisplayName("未实现的老人档案详情接口返回 404，不返回 500")
    void unimplementedElderDetailReturns404() throws Exception {
        createAccount("it_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String token = loginAndGetToken(mockMvc, "it_admin", ADMIN_PASSWORD);
        mockMvc.perform(get("/api/elders/2").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("未实现的健康记录详情接口返回 404，不返回 500")
    void unimplementedHealthDetailReturns404() throws Exception {
        createAccount("it_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String token = loginAndGetToken(mockMvc, "it_admin", ADMIN_PASSWORD);
        mockMvc.perform(get("/api/health-records/9999").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("请求方法不支持返回 405")
    void methodNotSupportedReturns405() throws Exception {
        createAccount("it_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String token = loginAndGetToken(mockMvc, "it_admin", ADMIN_PASSWORD);
        // /api/auth/login 只接受 POST
        mockMvc.perform(get("/api/auth/login").header("scc-test-token", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("对象不存在返回 404，且响应体不含内部实现细节")
    void missingObjectReturns404WithoutLeakingInternals() throws Exception {
        createAccount("it_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        String token = loginAndGetToken(mockMvc, "it_admin", ADMIN_PASSWORD);
        String body = mockMvc.perform(get("/api/alert-events/999999")
                        .header("scc-test-token", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 不得出现 SQL、堆栈、类名等内部信息
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("com.example")
                .doesNotContain("SELECT");
    }

    @Test
    @DisplayName("未登录时优先返回 401，不泄露路由是否存在")
    void anonymousGets401BeforeRouting() throws Exception {
        // 鉴权拦截在路由匹配之前执行：未登录时既不返回 404 也不返回 200，
        // 避免通过状态码差异探测出哪些接口存在
        mockMvc.perform(get("/api/definitely-not-a-real-endpoint"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("三个角色都能登录，角色事实由后端返回")
    void allThreeRolesCanLogin() throws Exception {
        createAccount("it_role_admin", ADMIN_PASSWORD, UserRole.COMMUNITY_ADMIN, true);
        createAccount("it_role_staff", STAFF_PASSWORD, UserRole.SERVICE_STAFF, true);
        createAccount("it_role_elder", ELDER_PASSWORD, UserRole.ELDER, true);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_role_admin","password":"%s"}
                                """.formatted(ADMIN_PASSWORD)))
                .andExpect(jsonPath("$.data.role").value("COMMUNITY_ADMIN"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_role_staff","password":"%s"}
                                """.formatted(STAFF_PASSWORD)))
                .andExpect(jsonPath("$.data.role").value("SERVICE_STAFF"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"it_role_elder","password":"%s"}
                                """.formatted(ELDER_PASSWORD)))
                .andExpect(jsonPath("$.data.role").value("ELDER"));
    }
}
