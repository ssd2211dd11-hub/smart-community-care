package org.imnu.smartcommunitycare.device.model.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改设备档案请求。
 *
 * <p>设备编号<b>不允许修改</b>：它有历史后是设备的对外身份，
 * 改了会让旧指令、旧日志对不上。归属老人允许改，因为现实中确实会转移设备。
 */
public record UpdateDeviceRequest(

        @Size(max = 50, message = "设备名称长度不能超过 50")
        String deviceName,

        Long elderId,

        @Size(max = 100, message = "安装位置长度不能超过 100")
        String location,

        /** 是否允许接入与控制 */
        Boolean enabled,

        /** 内部使用：设备类型变更范围受控，仅允许同类型覆盖 */
        @Pattern(regexp = "^(LIGHT|AIRCON|CURTAIN)$", message = "设备类型不合法")
        String deviceType
) {
}
