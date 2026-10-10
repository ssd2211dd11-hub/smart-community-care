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
 * 订单处理记录（追加式时间线）。
 *
 * <p>只追加不改写。一次状态变更与其日志同事务提交，
 * 不会出现"状态变了但查不到经过"或"有日志但状态没变"。
 */
@Getter
@Setter
@TableName("service_order_log")
public class ServiceOrderLog {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** CREATE/ASSIGN/ACCEPT/REJECT/TRANSFER/START/SUBMIT/CONFIRM/RETURN/CANCEL/TERMINATE */
    private String action;

    private String fromStatus;
    private String toStatus;

    /** 操作人；系统动作为空 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long operatorId;

    /** USER / SYSTEM */
    private String operatorSource;

    private String reason;

    private LocalDateTime createdAt;
}
