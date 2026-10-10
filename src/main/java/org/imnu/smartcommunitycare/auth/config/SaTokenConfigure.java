package org.imnu.smartcommunitycare.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截器配置。
 *
 * <p>核心原则（V1.0 §11.1）：<b>除公开路径外一律要求登录</b>，
 * 而不是逐个接口写 permitAll。默认拒绝比逐个放行安全——
 * 新增接口时忘记加校验，也不会变成裸奔接口。
 *
 * <p>角色与对象归属检查放在 Service 层做，拦截器只负责"是否登录"。
 * 这样数据权限不会被绕过（只查ID 不查范围的常见错误）。
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    /** 无需登录即可访问的路径 */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/health-check"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle ->
                        SaRouter.match("/api/**")
                                .notMatch(PUBLIC_PATHS)
                                .check(r -> StpUtil.checkLogin())))
                .addPathPatterns("/**");
    }
}
