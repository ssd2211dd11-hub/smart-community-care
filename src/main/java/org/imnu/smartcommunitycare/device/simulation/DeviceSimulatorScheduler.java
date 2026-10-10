package org.imnu.smartcommunitycare.device.simulation;

import org.imnu.smartcommunitycare.device.config.SimulatorProperties;
import org.imnu.smartcommunitycare.device.mapper.DeviceMapper;
import org.imnu.smartcommunitycare.device.model.entity.Device;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置家电模拟调度，生成心跳并处理开关指令，不连接真实硬件。
 * 单实例串行运行，每台设备使用独立事务；等待由调度间隔体现。
 * 多实例运行需要额外的调度协调。
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "care.simulator", name = "enabled", havingValue = "true")
public class DeviceSimulatorScheduler {

    private final DeviceMapper deviceMapper;
    private final DeviceSimulatorExecutor executor;
    private final SimulatorProperties properties;
    /** 单机串行轮询游标；到末尾后从头继续，避免最小 ID 占满每一批。 */
    private long lastDeviceId;

    /**
     * 调度一轮。
     *
     * <p>{@code fixedDelayString} 而不是 {@code fixedRate}：一轮执行耗时
     * 不应叠加到下一轮的开始时间上，否则设备多时轮次会越追越紧。
     */
    @Scheduled(
            fixedDelayString = "${care.simulator.schedule-interval-ms:1000}",
            initialDelayString = "${care.simulator.schedule-interval-ms:1000}")
    public void runOnce() {
        List<Device> candidates;
        try {
            candidates = deviceMapper.findSchedulerCandidates(lastDeviceId, properties.getBatchSize());
            if (candidates.isEmpty() && lastDeviceId != 0) {
                lastDeviceId = 0;
                candidates = deviceMapper.findSchedulerCandidates(0, properties.getBatchSize());
            }
        } catch (Exception e) {
            // 候选查询失败就跳过这一轮，不要让异常冒到调度线程
            // 而中断后续所有调度
            log.warn("模拟器本轮候选查询失败，跳过：{}", e.getMessage());
            return;
        }

        for (Device device : candidates) {
            lastDeviceId = device.getId();
            try {
                executor.tick(device.getId());
            } catch (Exception e) {
                // 单台设备失败不影响其他设备。这里不打堆栈：
                // 一台设备持续失败会刷屏，消息已足够定位
                log.warn("设备 {} 本轮模拟失败：{}", device.getId(), e.getMessage());
            }
        }
    }
}
