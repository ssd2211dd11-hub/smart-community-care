package org.imnu.smartcommunitycare.device.model.request;
import lombok.Getter;
import lombok.Setter;
import org.imnu.smartcommunitycare.common.api.PageQuery;
@Getter @Setter
public class DevicePageQuery extends PageQuery {
    private String keyword;
    private String deviceType;
    private String onlineStatus;
    private Long elderId;
    private Boolean enabled;
}
