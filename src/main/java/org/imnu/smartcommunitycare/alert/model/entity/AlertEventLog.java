package org.imnu.smartcommunitycare.alert.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 事件处理记录（追加式时间线）。
 *
 * <p>只追加不改写。一次状态变更与其日志要么都成功、要么都回滚，
 * 否则会出现"状态变了但查不到经过"或"有日志但状态没变"的错位。
 */
@Getter
@Setter
@TableName("alert_event_log")
public class AlertEventLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long eventId;

    /** CREATE/CLAIM/ASSIGN/ACCEPT/TRANSFER/CLOSE/CANCEL */
    private String action;

    private String fromStatus;

    private String toStatus;

    /** 操作人账号；系统自动动作为空 */
    private Long operatorId;

    /** USER / SYSTEM */
    private String operatorSource;

    private String reason;

    private LocalDateTime createdAt;
}
