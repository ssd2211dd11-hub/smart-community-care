package org.imnu.smartcommunitycare.device.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建设备请求。
 *
 * <p>{@code accessMode} 不开放输入：首版固定为 SIMULATED，
 * 页面不需要也不能选择接入模式——让用户以为能选真实接入是危险的误导。
 */
public record CreateDeviceRequest(

        @NotBlank(message = "请填写设备编号")
        @Pattern(regexp = "^[A-Za-z0-9_-]{4,32}$",
                message = "设备编号只能包含字母、数字、下划线和短横线，长度 4~32")
        String deviceNo,

        @NotBlank(message = "请填写设备名称")
        @Size(max = 50, message = "设备名称长度不能超过 50")
        String deviceName,

        @NotBlank(message = "请选择设备类型")
        @Pattern(regexp = "^(LIGHT|AIRCON|CURTAIN)$",
                message = "设备类型只能是 LIGHT、AIRCON 或 CURTAIN")
        String deviceType,

        /** 归属老人档案，可为空表示尚未分配 */
        Long elderId,

        @Size(max = 100, message = "安装位置长度不能超过 100")
        String location
) {
}
