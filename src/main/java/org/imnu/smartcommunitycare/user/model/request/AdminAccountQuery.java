package org.imnu.smartcommunitycare.user.model.request;
import lombok.Getter;
import lombok.Setter;
import org.imnu.smartcommunitycare.common.api.PageQuery;
@Getter @Setter
public class AdminAccountQuery extends PageQuery {
    private String keyword;
    private String role;
    private Boolean enabled;
}
