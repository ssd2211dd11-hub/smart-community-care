package org.imnu.smartcommunitycare.device.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 设备档案（含内置模拟器状态）。
 *
 * <p><b>设备数据均为模拟</b>，页面必须明示「系统内置模拟，非真实硬件」。
 * 字段按语义分三组，缺一不可：
 * <ul>
 *   <li>业务状态：enabled（是否允许接入与控制）</li>
 *   <li>模拟器状态：simulator_running、simulation_mode、simulated_power_status</li>
 *   <li>上报状态：power_status、last_seen_at</li>
 * </ul>
 * 模拟器自身状态与上报状态必须分开，才能演示「设备已改变但结果未确认」——
 * {@code simulated_power_status} 变了而 {@code power_status} 没变，
 * 就是「结果未确认」的真实数据形态。
 */
@Getter
@Setter
@TableName("device")
public class Device {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号，有历史后不修改 */
    private String deviceNo;

    private String deviceName;

    /** LIGHT / AIRCON / CURTAIN */
    private String deviceType;

    /** 当前所属老人档案，可为空（未分配） */
    private Long elderId;

    /** 首版固定 SIMULATED */
    private String accessMode;

    private String location;

    /** 业务停用：禁止新的接入与控制，并使待执行指令失效 */
    private Boolean enabled;

    /** 管理员是否已启动内置虚拟设备 */
    private Boolean simulatorRunning;

    /** NORMAL / FAIL_NEXT / NO_ACK */
    private String simulationMode;

    /** 虚拟设备自身当前的开关状态 */
    private String simulatedPowerStatus;

    /** 最近一次模拟心跳报告的开关状态，首次心跳前为空 */
    private String powerStatus;

    /** 最近一次有效心跳时刻，由后端写入，不接受客户端提供 */
    private LocalDateTime lastSeenAt;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
