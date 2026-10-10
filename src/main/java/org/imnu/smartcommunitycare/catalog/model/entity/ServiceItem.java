package org.imnu.smartcommunitycare.catalog.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 社区服务项目。
 *
 * <p>{@code price} 是参考价格，不是收入，也不代表任何支付已完成。
 */
@Getter
@Setter
@TableName("service_item")
public class ServiceItem {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String name;

    /** HOUSEKEEPING / MEDICAL / DELIVERY / CULTURE */
    private String category;

    private String description;

    /** 参考价格，不是收入 */
    private BigDecimal price;

    private String provider;

    private String phone;

    /** 下架只阻止新预约，不取消已有订单 */
    private Boolean enabled;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
