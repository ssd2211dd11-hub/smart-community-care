package org.imnu.smartcommunitycare.order.model.request;
import lombok.Getter;
import lombok.Setter;
import org.imnu.smartcommunitycare.common.api.PageQuery;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;
@Getter @Setter
public class OrderPageQuery extends PageQuery {
    private String status;
    private String scope="ALL";
    private Long elderId;
    private Long staffId;
    private Boolean excludeEnded;
    private String dateField="APPOINTMENT";
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) private LocalDate fromDate;
    @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) private LocalDate toDate;
}
