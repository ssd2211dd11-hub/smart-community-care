package org.imnu.smartcommunitycare.order.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 服务评价。
 *
 * <p>数据库唯一约束 {@code uk_service_review_order} 保证<b>一单一评</b>，
 * 不靠应用层判断——并发提交时唯一键会直接拦下第二条。
 */
@Getter
@Setter
@TableName("service_review")
public class ServiceReview {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 评价人必须是订单所属老人 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long elderId;

    /** 1~5 分 */
    private Integer rating;

    /** 按普通文本展示，不渲染 HTML */
    private String content;

    private LocalDateTime createdAt;
}
