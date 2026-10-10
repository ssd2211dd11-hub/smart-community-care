package org.imnu.smartcommunitycare.order;

import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.request.VersionGuard;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VersionGuardContractTest {
    private ServiceOrder order(int version) {
        ServiceOrder order = new ServiceOrder();
        order.setVersion(version);
        return order;
    }

    @Test
    void missingVersionIsRejected() {
        assertThatThrownBy(() -> VersionGuard.resolve(null, order(0), "接单"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
    }

    @Test
    void equalVersionsOutsideIntegerCacheAreAccepted() {
        assertThat(VersionGuard.resolve(Integer.valueOf("131"), order(131), "提交结果")).isEqualTo(131);
    }

    @Test
    void staleVersionIsRejectedWithBothVersions() {
        assertThatThrownBy(() -> VersionGuard.resolve(130, order(131), "提交结果"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessage()).contains("130", "131", "刷新");
                });
    }

    @Test
    void negativeVersionIsInvalid() {
        assertThatThrownBy(() -> VersionGuard.resolve(-1, order(0), "接单"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
    }
}
