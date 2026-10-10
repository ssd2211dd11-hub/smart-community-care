package org.imnu.smartcommunitycare.alert.model.request;
import lombok.Getter;
import lombok.Setter;
import org.imnu.smartcommunitycare.common.api.PageQuery;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
@Getter @Setter
public class AlertPageQuery extends PageQuery {
    private Long elderId;
    private String scope="CURRENT";
    private String status;
    private String sourceType;
    private Boolean unresponded;
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) private LocalDate fromDate;
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) private LocalDate toDate;
}
