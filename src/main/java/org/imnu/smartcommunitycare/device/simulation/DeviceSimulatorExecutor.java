package org.imnu.smartcommunitycare.device.simulation;

import org.imnu.smartcommunitycare.device.config.SimulatorProperties;
import org.imnu.smartcommunitycare.device.mapper.DeviceCommandMapper;
import org.imnu.smartcommunitycare.device.mapper.DeviceMapper;
import org.imnu.smartcommunitycare.device.model.entity.Device;
import org.imnu.smartcommunitycare.device.model.entity.DeviceCommand;
import org.imnu.smartcommunitycare.device.model.enums.DeviceCommandStatus;
import org.imnu.smartcommunitycare.device.model.enums.PowerStatus;
import org.imnu.smartcommunitycare.device.model.enums.SimulationMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 单台虚拟设备的一轮执行。
 *
 * <p><b>为什么单独一个类：</b>{@code @Transactional} 靠代理生效，
 * 同一个类里内部方法互调不经过代理，事务不会开启。
 * 调度入口遍历设备逐个调用本类的方法，事务才能一设备一事务——
 * 一台设备出错不该让整轮回滚。
 *
 * <p>每台设备的处理顺序固定为：收口过期指令 → 执行待执行指令 → 按需心跳。
 * 顺序不能换：先把到期的收口，才不会在本轮继续执行一条已经过期的指令。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceSimulatorExecutor {

    private final DeviceMapper deviceMapper;
    private final DeviceCommandMapper commandMapper;
    private final SimulatorProperties properties;
    private final Clock clock;

    /**
     * 处理一台设备的一轮。
     *
     * <p><b>设备行锁在最前</b>：控制指令提交也先锁设备，
     * 同一台设备的「下发」与「执行」因此串行，不会出现
     * 模拟器执行了一半、提交线程又把指令塞进来的情况。
     */
    @Transactional(rollbackFor = Exception.class)
    public void tick(Long deviceId) {
        Device device = deviceMapper.findByIdForUpdate(deviceId);
        if (device == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);

        // 停用设备：不执行任何指令，也不上报心跳，但仍允许收口过期指令，
        // 否则停用前提交的指令会永远停在 PENDING
        if (!Boolean.TRUE.equals(device.getEnabled())) {
            timeoutExpired(device, now);
            return;
        }

        timeoutExpired(device, now);

        if (Boolean.TRUE.equals(device.getSimulatorRunning())) {
            boolean executedPowerChange = executePendingCommand(device, now);
            // 心跳必须在指令执行之后发，并且重读设备状态：
            // 前面刚改过 simulated_power_status，手上的 device 对象是旧值。
            // 顺序反过来会出现「指令已成功，但上报的还是开之前的 OFF」，
            // 与实际执行结果矛盾。
            if (executedPowerChange) {
                device = deviceMapper.selectById(deviceId);
            }
            maybeHeartbeat(device, now);
        }
    }

    /**
     * 收口过期指令。
     *
     * <p>独立于模拟器运行状态执行：模拟器停了、指令还在，
     * 也要把它落成 TIMED_OUT，否则页面永远显示「待执行」。
     */
    private void timeoutExpired(Device device, LocalDateTime now) {
        int timedOut = commandMapper.timeoutExpired(device.getId(), now);
        if (timedOut > 0) {
            log.debug("设备 {} 有 {} 条指令到期未确认，落为 TIMED_OUT", device.getId(), timedOut);
        }
    }

    /**
     * 执行待处理指令。
     *
     * <p>三条前置条件缺一不可：
     * <ul>
     *   <li>有指令（{@code findPending}）；</li>
     *   <li>已过 {@code ready_at}——制造「已提交但还没生效」的可见中间态；</li>
     *   <li>未过 {@code expires_at}——过期指令不该被"补执行"，它已经是未知结果了。</li>
     * </ul>
     */
    private boolean executePendingCommand(Device device, LocalDateTime now) {
        DeviceCommand command = commandMapper.findPending(device.getId());
        if (command == null) {
            return false;
        }
        if (now.isBefore(command.getReadyAt())) {
            return false;
        }
        if (!now.isBefore(command.getExpiresAt())) {
            // 已在 timeoutExpired 里收口，这里只是防御：
            // 若走到说明两步之间状态被改过，交给下一轮，不重复执行
            return false;
        }

        SimulationMode mode = parseMode(device.getSimulationMode());
        return switch (mode) {
            case NORMAL -> executeNormally(device, command, now);
            case FAIL_NEXT -> { executeFailingOnce(device, command, now); yield false; }
            case NO_ACK -> executeWithoutAck(device, command, now);
        };
    }

    /** NORMAL：设置目标开关并确认结果 */
    private boolean executeNormally(Device device, DeviceCommand command, LocalDateTime now) {
        if (!lockStillPending(command.getId())) {
            return false;
        }
        String target = command.getTargetPowerStatus();

        requireWritten(deviceMapper.updateSimulatedPowerStatus(device.getId(), target, now));
        int rows = commandMapper.markSucceeded(command.getId(), target, now);
        requireWritten(rows);
        return true;
    }

    /**
     * FAIL_NEXT：本次失败一次，开关不变，场景立刻恢复 NORMAL。
     *
     * <p>「一次性」很关键：如果持续失败就没法演示正常执行了，
     * 而且用户会以为是设备坏了，而不是模拟场景生效。
     */
    private void executeFailingOnce(Device device, DeviceCommand command, LocalDateTime now) {
        if (!lockStillPending(command.getId())) {
            return;
        }
        String reason = "模拟器按 FAIL_NEXT 场景故意失败一次（设备开关未改变）";
        int rows = commandMapper.markFailed(command.getId(), reason, now);
        if (rows > 0) {
            deviceMapper.updateSimulationMode(device.getId(),
                    SimulationMode.NORMAL.name(), now);
        }
    }

    /**
     * NO_ACK：设备已改变开关，但不回报结果。
     *
     * <p>指令保持 PENDING，由 {@code timeoutExpired} 到期收口。
     * 这里<b>必须</b>写 {@code simulated_executed_at}，
     * 否则每轮调度都会把同一条再执行一次——虽然目标状态相同看不出异常，
     * 但把「已执行」当成「待执行」重复处理是掩盖问题的做法。
     *
     * <p>执行后设备侧状态与上报状态会不一致，这正是要展示的数据形态：
     * 设备确实改了，但平台不知道结果。
     */
    private boolean executeWithoutAck(Device device, DeviceCommand command, LocalDateTime now) {
        if (!lockStillPending(command.getId())) {
            return false;
        }
        // 已执行过就不能再执行一次。
        // 只靠 markExecutedWithoutAck 写 simulated_executed_at 是不够的——
        // 指令状态仍是 PENDING，下一轮所有前置条件都满足，会被再执行一遍。
        // 每轮重写执行时间会让「执行时刻」失去意义，也让「只执行一次」这条
        // 演示要点无法验证。
        if (command.getSimulatedExecutedAt() != null) {
            return false;
        }
        String target = command.getTargetPowerStatus();
        requireWritten(deviceMapper.updateSimulatedPowerStatus(device.getId(), target, now));
        requireWritten(commandMapper.markExecutedWithoutAck(command.getId(), now));
        return true;
    }

    private void requireWritten(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("模拟状态与必要执行记录写入不一致，本轮回滚");
        }
    }

    /**
     * 到心跳时间则上报一次。
     *
     * <p>首次心跳立刻发（{@code last_seen_at} 为空），否则新启动的设备
     * 要等一个完整周期才出现在线状态。
     */
    private void maybeHeartbeat(Device device, LocalDateTime now) {
        if (!properties.isHeartbeatDue(device.getLastSeenAt(), now)) {
            return;
        }
        String reported = device.getSimulatedPowerStatus() == null
                ? PowerStatus.OFF.name()
                : device.getSimulatedPowerStatus();
        deviceMapper.writeHeartbeat(device.getId(), reported, now);
    }

    /**
     * 加锁重读指令，确认它仍是 PENDING。
     *
     * <p>调度开始时拿到的指令对象可能已过期：到期收口、用户取消、
     * 另一轮调度抢先执行，都会在这中间改掉它的状态。
     * 用旧对象判断就会对本不该执行的指令动手。
     */
    private boolean lockStillPending(Long commandId) {
        DeviceCommand fresh = commandMapper.findByIdForUpdate(commandId);
        return fresh != null
                && DeviceCommandStatus.PENDING.name().equals(fresh.getStatus());
    }

    private SimulationMode parseMode(String value) {
        try {
            return SimulationMode.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            // 库里出现未知场景值时按 NORMAL 处理，不要让整轮调度崩掉
            log.warn("未知的模拟场景值 {}，本轮按 NORMAL 处理", value);
            return SimulationMode.NORMAL;
        }
    }
}
