package org.imnu.smartcommunitycare.common;

import org.imnu.smartcommunitycare.common.api.PageQuery;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {
    @Test void largestAcceptedPageDoesNotOverflowOffset() {
        PageQuery query = new PageQuery(Integer.MAX_VALUE, 100);
        assertThat(query.offset()).isEqualTo(214748364600L);
    }

    @Test void invalidPageAndSizeUseExistingSafeDefaults() {
        PageQuery query = new PageQuery(-1, 0);
        assertThat(query.safePageNum()).isEqualTo(1);
        assertThat(query.safePageSize()).isEqualTo(20);
        assertThat(query.offset()).isZero();
        query.setPageSize(1000);
        assertThat(query.safePageSize()).isEqualTo(100);
    }
}
