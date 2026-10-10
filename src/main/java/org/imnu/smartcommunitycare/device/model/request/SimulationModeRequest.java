package org.imnu.smartcommunitycare.device.model.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 切换模拟执行场景请求。
 */
public record SimulationModeRequest(

        @NotBlank(message = "请选择执行场景")
        @Pattern(regexp = "^(NORMAL|FAIL_NEXT|NO_ACK)$",
                message = "执行场景只能是 NORMAL、FAIL_NEXT 或 NO_ACK")
        String mode
) {
}
