package org.imnu.smartcommunitycare.device.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 模拟控制指令。
 *
 * <p>指令是「提交」与「执行」两段式：浏览器提交后只得到 PENDING，
 * 由内置调度器在后续轮次执行并改为终态。接口返回成功<b>只表示指令已保存</b>，
 * 不表示设备已动作。
 */
@Getter
@Setter
@TableName("device_command")
public class DeviceCommand {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long deviceId;

    /** 创建时的老人归属，转移归属不改写历史 */
    private Long elderIdSnapshot;

    /** 提交人账号，模拟执行不改写 */
    private Long requestedBy;

    /** 幂等键：同一次提交重试沿用同一个 */
    private String requestId;

    /** 标准化请求内容摘要，同键不同内容据此判冲突 */
    private String requestHash;

    /** 首版只有 SET_POWER，不用 TOGGLE（重试会反复翻转） */
    private String action;

    private String targetPowerStatus;

    private String status;

    /** 执行后的虚拟开关状态 */
    private String resultPowerStatus;

    private String reason;

    /** 允许执行的时刻，创建时加一个演示延迟 */
    private LocalDateTime readyAt;

    /** 到期未确认即按 TIMED_OUT 展示 */
    private LocalDateTime expiresAt;

    /** 虚拟执行时刻。NO_ACK 也写，防止每轮重复执行同一条 */
    private LocalDateTime simulatedExecutedAt;

    private LocalDateTime acknowledgedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
