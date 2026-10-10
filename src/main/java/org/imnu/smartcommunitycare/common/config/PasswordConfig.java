package org.imnu.smartcommunitycare.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 密码编码器。
 *
 * <p>只引入 spring-security-crypto 这一个包，用于 BCrypt 哈希。
 * <b>不启用 Spring Security 的过滤器链</b>——认证由 Sa-Token 负责，
 * 保留一套全放行的安全链只会让人误以为有防护。
 */
@Configuration
public class PasswordConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
