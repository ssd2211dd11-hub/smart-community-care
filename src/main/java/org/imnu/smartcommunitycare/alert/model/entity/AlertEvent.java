package org.imnu.smartcommunitycare.alert.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.imnu.smartcommunitycare.alert.model.enums.AlertStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 告警事件实体。
 *
 * <p>健康告警与主动求助共用本表，用 {@code sourceType} 区分。
 * 两类事件的去重方式不同：健康按「老人 + 规则」归并，求助按 requestId 防重。
 */
@Getter
@Setter
@TableName("alert_event")
public class AlertEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编号，AE 前缀 */
    private String eventNo;

    /** HEALTH_ALERT / HELP_CALL */
    private String sourceType;

    /** 健康事件的规则编码；主动求助为空 */
    private String ruleCode;

    /** 老人档案 ID */
    private Long elderId;

    /** PENDING / ASSIGNED / PROCESSING / CLOSED / CANCELLED */
    private String status;

    /** HIGH / NORMAL，仅用于待办排序，不代表医学严重程度 */
    private String priority;

    /** 当前处理人账号 */
    private Long handlerId;

    /** 归并后的发生次数 */
    private Integer occurrenceCount;

    /** 首次响应时限，创建时按配置计算，归并时不顺延 */
    private LocalDateTime responseDueAt;

    /** 首次接收时间，只写一次，转派不清空 */
    private LocalDateTime firstAcceptedAt;

    private LocalDateTime closedAt;

    /** RESOLVED / FALSE_ALARM / DATA_CORRECTION */
    private String closeResult;

    private String closeNote;

    /** 主动求助的事项类型 */
    private String callType;

    private String contactPhone;

    private String description;

    /** 主动求助防重键；健康自动事件为空 */
    private String requestId;

    private String requestHash;

    /** 乐观锁版本，状态修改时校验 */
    private Integer version;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean isOpen() {
        return AlertStatus.from(status).isOpen();
    }
}
