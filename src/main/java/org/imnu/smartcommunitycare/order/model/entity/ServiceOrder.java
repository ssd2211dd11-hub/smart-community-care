package org.imnu.smartcommunitycare.order.model.entity;

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
 * 服务预约订单。
 *
 * <p><b>快照字段的意义</b>：{@code serviceNameSnapshot}、{@code priceSnapshot}、
 * {@code slotStartSnapshot}、{@code contactPhone} 等在创建时定格。
 * 服务改价、老人改地址，都<b>不影响已有订单</b>——已约定好的服务按约定执行。
 *
 * <p>{@code amount} 类字段是参考价格，不代表支付成功或实收收入。本项目没有支付流程。
 */
@Getter
@Setter
@TableName("service_order")
public class ServiceOrder {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务编号，SO 前缀 */
    private String orderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long elderId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long slotId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long serviceId;

    /** 当前受派服务人员账号 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long staffId;

    private String status;

    /** 服务名快照 */
    private String serviceNameSnapshot;

    /** 参考价格快照，不代表实收 */
    private BigDecimal priceSnapshot;

    private LocalDateTime slotStartSnapshot;

    private LocalDateTime slotEndSnapshot;

    private String contactName;

    private String contactPhone;

    private String addressSnapshot;

    /** 服务人员提交的结果说明 */
    private String serviceResult;

    private LocalDateTime acceptedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime terminatedAt;
    private LocalDateTime cancelledAt;

    private String cancelReason;

    /** 乐观锁版本 */
    private Integer version;

    private String requestId;
    private String requestHash;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
