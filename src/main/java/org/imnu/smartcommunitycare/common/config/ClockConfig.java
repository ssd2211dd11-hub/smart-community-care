package org.imnu.smartcommunitycare.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时间源。
 *
 * <p>把"现在几点"做成注入的 {@link Clock}，而不是各处直接调
 * {@code LocalDateTime.now()}：时间边界（七日范围、作废时间、超时判定）
 * 必须可测试，否则只能靠等真实时间去验证，出错也定位不了。
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
