package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.BaseIntegrationTest;
import org.imnu.smartcommunitycare.order.model.entity.*;
import org.imnu.smartcommunitycare.catalog.model.entity.*;
import org.imnu.smartcommunitycare.user.model.response.UserResponse;
import org.imnu.smartcommunitycare.auth.model.response.LoginResponse;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** 使用当前应用的真实 mapper 与真实返回类型，不改数据库主键。 */
@SpringBootTest
class P3IdentifierContractTest extends BaseIntegrationTest {
    @Autowired ObjectMapper json;
    private static final Long LARGE = 9007199254740993L;

    private void ids(Object value, String... fields) {
        var tree=json.readTree(json.writeValueAsString(value));
        for(String field:fields) {
            assertThat(tree.path(field).isString()).as(field+" 必须为字符串").isTrue();
            assertThat(tree.path(field).asString()).isEqualTo(LARGE.toString());
        }
    }

    @Test void largeCatalogIdsRetainExactDecimalValue() {
        ServiceItem item=new ServiceItem(); item.setId(LARGE); item.setPrice(new java.math.BigDecimal("10.50"));
        ids(item,"id");
        ServiceSlot slot=new ServiceSlot(); slot.setId(LARGE); slot.setServiceId(LARGE); slot.setCapacity(5); slot.setOccupiedCount(1);
        ids(slot,"id","serviceId");
        var tree=json.readTree(json.writeValueAsString(slot));
        assertThat(tree.path("capacity").isIntegralNumber()).isTrue();
        assertThat(tree.path("occupiedCount").isIntegralNumber()).isTrue();
    }
    @Test void largeOrderAndTimelineIdsRetainExactDecimalValue() {
        ServiceOrder order=new ServiceOrder(); order.setId(LARGE); order.setElderId(LARGE);
        order.setServiceId(LARGE); order.setSlotId(LARGE); order.setStaffId(LARGE); order.setVersion(131);
        ids(order,"id","elderId","serviceId","slotId","staffId");
        assertThat(json.readTree(json.writeValueAsString(order)).path("version").isIntegralNumber()).isTrue();
        order.setStaffId(null); var absent=json.readTree(json.writeValueAsString(order)).get("staffId");
        assertThat(absent==null || absent.isNull()).isTrue();
        ServiceOrderLog log=new ServiceOrderLog(); log.setId(LARGE); log.setOrderId(LARGE); log.setOperatorId(LARGE);
        ids(log,"id","orderId","operatorId");
    }
    @Test void largeReviewAndProfileIdsRetainExactDecimalValue() {
        ServiceReview review=new ServiceReview(); review.setId(LARGE); review.setOrderId(LARGE); review.setElderId(LARGE); review.setRating(5);
        ids(review,"id","orderId","elderId");
        assertThat(json.readTree(json.writeValueAsString(review)).path("rating").isIntegralNumber()).isTrue();
        ids(new UserResponse(LARGE,"fixture","夹具",null,null,UserRole.ELDER,true,null,null,null,null,null,null),"id");
        ids(new LoginResponse("fixture",LARGE,"fixture","夹具","ELDER"),"userId");
    }
}
