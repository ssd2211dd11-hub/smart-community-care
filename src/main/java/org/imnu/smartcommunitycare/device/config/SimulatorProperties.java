package org.imnu.smartcommunitycare.device.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内置设备模拟器参数。
 *
 * <p><b>这些参数只影响模拟器的行为节奏，不影响任何真实设备。</b>
 * 首版设备全部为 SIMULATED，不接真实硬件，参数值按「答辩时能在几十秒内
 * 看到完整状态流转」来定，而不是按真实硬件的通信频率。
 *
 * <p>{@code enabled=false} 时不注册调度入口，设备模拟接口直接拒绝，
 * 避免误以为有真实设备在被控制。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "care.simulator")
public class SimulatorProperties {

    /** 总开关。关闭时模拟器完全停摆，模拟控制接口不可用 */
    private boolean enabled = false;

    /** 调度轮询间隔（毫秒） */
    private long scheduleIntervalMs = 1000L;

    /** 虚拟设备心跳间隔（毫秒） */
    private long heartbeatIntervalMs = 5000L;

    /**
     * 离线判定阈值（毫秒）。
     *
     * <p>取值必须显著大于心跳间隔，否则一次调度抖动就会被判离线。
     */
    private long offlineThresholdMs = 20000L;

    /** 指令提交后延迟多久才允许执行，制造「已提交但未生效」的可见中间态 */
    private long commandReadyDelayMs = 2000L;

    /** 指令有效期（毫秒），到期未确认即 TIMED_OUT */
    private long commandExpireMs = 30000L;

    /** 单轮最多处理的设备数，防止设备量增长后一轮事务过长 */
    private int batchSize = 50;

    /**
     * 心跳判定是否到期。
     *
     * <p>抽成方法而不是在调度器里写散落的算术：离线阈值与心跳间隔的
     * 大小关系是业务约定，放在一处才好读、好改。
     */
    public boolean isHeartbeatDue(java.time.LocalDateTime lastSeenAt,
                                  java.time.LocalDateTime now) {
        if (lastSeenAt == null) {
            return true;
        }
        return !lastSeenAt.plusNanos(heartbeatIntervalMs * 1_000_000L).isAfter(now);
    }

    /** 离线判定：超过阈值未收到有效心跳 */
    public boolean isOffline(java.time.LocalDateTime lastSeenAt,
                             java.time.LocalDateTime now) {
        if (lastSeenAt == null) {
            return true;
        }
        return lastSeenAt.plusNanos(offlineThresholdMs * 1_000_000L).isBefore(now);
    }

    /**
     * 校验参数自洽。
     *
     * <p>离线阈值不大于心跳间隔时，设备会在两次心跳之间被判离线，
     * 表现为「刚上报就不在线」的抖动。这类错误在启动时报错比运行时
     * 让演示现场困惑要好。
     */
    @jakarta.annotation.PostConstruct
    public void validate() {
        if (offlineThresholdMs <= heartbeatIntervalMs) {
            throw new IllegalStateException(
                    "care.simulator.offline-threshold-ms 必须大于 heartbeat-interval-ms，"
                            + "否则设备会在两次心跳之间被判离线");
        }
        if (commandExpireMs <= commandReadyDelayMs) {
            throw new IllegalStateException(
                    "care.simulator.command-expire-ms 必须大于 command-ready-delay-ms，"
                            + "否则指令一创建就已过期");
        }
    }
}