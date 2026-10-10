package org.imnu.smartcommunitycare;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 智慧社区居家养老服务与告警协同平台 —— 启动类。
 *
 * <p>Mapper 扫描限制在根包内带 {@code @Mapper} 注解的接口，不宽泛扫描所有 Service。
 * 业务包划分见各模块包说明，模块内按 controller / service / mapper / model 分层。
 *
 * <p>开启调度是为了内置设备模拟器（见 {@code device.simulation}）。
 * 该调度器用 {@code @ConditionalOnProperty} 控制，
 * 配置关闭时 bean 不会注册，{@code @EnableScheduling} 本身不产生任何行为。
 */
@EnableScheduling
@SpringBootApplication
@MapperScan("org.imnu.smartcommunitycare")
public class SmartCommunityCareApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartCommunityCareApplication.class, args);
    }

}