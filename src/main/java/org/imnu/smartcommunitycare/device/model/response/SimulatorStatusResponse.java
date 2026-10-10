package org.imnu.smartcommunitycare.device.model.response;

import java.util.List;

/** 空设备列表时也可查询总引擎状态，不包含运行凭据。 */
public record SimulatorStatusResponse(boolean globalEnabled, String businessDate,
                                      String businessZone, List<String> supportedTypes) {
}
