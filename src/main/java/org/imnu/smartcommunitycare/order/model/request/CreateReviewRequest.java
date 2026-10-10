package org.imnu.smartcommunitycare.order.model.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 提交评价请求。
 *
 * <p>只包含允许输入的评分和文字。评价人、订单归属、完成状态、实际服务人员
 * 一律由后端确定，不接受前端传入。
 */
public record CreateReviewRequest(

        @NotNull(message = "请填写评分")
        @Min(value = 1, message = "评分最低 1 分")
        @Max(value = 5, message = "评分最高 5 分")
        Integer rating,

        @Size(max = 500, message = "评价内容长度不能超过 500")
        String content
) {
}
