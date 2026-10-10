package org.imnu.smartcommunitycare.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEventLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 事件处理记录数据访问。
 */
@Mapper
public interface AlertEventLogMapper extends BaseMapper<AlertEventLog> {

    /** 某事件的时间线，按发生顺序 */
    @Select("SELECT * FROM alert_event_log WHERE event_id = #{eventId} ORDER BY created_at ASC, id ASC")
    List<AlertEventLog> findByEventId(@Param("eventId") Long eventId);

    record Participation(String eventNo,String sourceType,String currentStatus,String action,java.time.LocalDateTime actionAt) {}
    @Select("""
            SELECT e.event_no,e.source_type,e.status AS current_status,l.action,l.created_at AS action_at
            FROM alert_event_log l JOIN alert_event e ON e.id=l.event_id
            WHERE l.operator_id=#{staffId}
            ORDER BY l.created_at DESC,l.id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<Participation> participationPage(@Param("staffId") Long staffId,@Param("limit") int limit,@Param("offset") long offset);
    @Select("SELECT COUNT(*) FROM alert_event_log WHERE operator_id=#{staffId}")
    long participationCount(@Param("staffId") Long staffId);
}
