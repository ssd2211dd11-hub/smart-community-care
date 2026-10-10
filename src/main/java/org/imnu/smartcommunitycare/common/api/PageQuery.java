package org.imnu.smartcommunitycare.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页查询入参基类。列表接口必须服务端分页，不一次返回全表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageQuery {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数，默认 20，最大 100 */
    private Integer pageSize = DEFAULT_PAGE_SIZE;

    public int safePageNum() {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    public int safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    public long offset() {
        return (safePageNum() - 1L) * safePageSize();
    }

    /**
     * 分页结果。
     *
     * @param <T> 记录类型
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageResult<T> {

        private List<T> records;
        private long total;
        private int pageNum;
        private int pageSize;

        public static <T> PageResult<T> of(List<T> records, long total, int pageNum, int pageSize) {
            return new PageResult<>(records, total, pageNum, pageSize);
        }

        public static <T> PageResult<T> empty(int pageNum, int pageSize) {
            return new PageResult<>(List.of(), 0L, pageNum, pageSize);
        }
    }
}
