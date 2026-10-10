package org.imnu.smartcommunitycare.device.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 模拟控制指令请求。
 *
 * <p>目标状态必填且只能是 ON/OFF。<b>刻意不提供 TOGGLE</b>：
 * 切换类操作在重试时语义不清——同一条请求重试两次，开关会翻转回去。
 * 指令要幂等，目标状态必须是绝对值。
 *
 * <p>不提供备注字段：device_command 表没有备注列，reason 是执行结果
 * （失败原因、超时说明），不是提交时的留言。没有列就不开放输入，
 * 不做「接口收了但没地方存」的字段。
 */
public record SimulatorCommandRequest(

        @NotBlank(message = "请选择目标开关状态")
        @Pattern(regexp = "^(ON|OFF)$", message = "目标状态只能是 ON 或 OFF")
        String targetPowerStatus,

        @Size(max = 64, message = "请求标识长度不能超过 64")
        String requestId
) {
}
