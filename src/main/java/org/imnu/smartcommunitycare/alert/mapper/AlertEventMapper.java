package org.imnu.smartcommunitycare.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 事件数据访问。
 */
@Mapper
public interface AlertEventMapper extends BaseMapper<AlertEvent> {

    /**
     * 查某位老人某条规则的<b>未结</b>事件（归并目标）。
     *
     * <p><b>用锁定读 {@code FOR UPDATE}</b>：归并的判据是"当前没有未结事件"，
     * 若用普通 SELECT，两个并发请求会同时查到"没有"并各建一条，
     * 归并就失效了。锁定读把同一老人同一规则的并发串行化。
     *
     * <p>先筛老人与规则再取状态，命中 {@code idx_alert_event_elder_rule_status}。
     */
    @Select("""
            SELECT * FROM alert_event
            WHERE elder_id = #{elderId}
              AND rule_code = #{ruleCode}
              AND status IN ('PENDING', 'ASSIGNED', 'PROCESSING')
            ORDER BY id ASC
            LIMIT 1
            FOR UPDATE
            """)
    AlertEvent findOpenEventForUpdate(@Param("elderId") Long elderId,
                                      @Param("ruleCode") String ruleCode);

    /**
     * 锁定一条事件。
     *
     * <p>所有状态变更都先锁这一行，避免两个管理员同时认领同一事件
     * 都"成功"。
     */
    @Select("SELECT * FROM alert_event WHERE id = #{eventId} FOR UPDATE")
    AlertEvent findByIdForUpdate(@Param("eventId") Long eventId);

    /**
     * 归并：追加发生次数。
     *
     * <p><b>只改次数，不动 status / handler_id / response_due_at /
     * first_accepted_at</b>：追加新证据不应把已认领的事项退回待处理，
     * 也不应顺延响应截止时间或抹掉首次响应记录。
     */
    @Update("""
            UPDATE alert_event
            SET occurrence_count = occurrence_count + 1,
                updated_at = NOW(3)
            WHERE id = #{eventId}
            """)
    int appendOccurrence(@Param("eventId") Long eventId);

    /**
     * 带乐观锁的状态更新。
     *
     * <p>同时校验 {@code version}：页面停留期间别人改过状态时，
     * 影响行数为 0，调用方据此提示刷新，而不是覆盖别人的修改。
     *
     * @return 影响行数，1 表示成功
     */
    @Update("""
            UPDATE alert_event
            SET status = #{toStatus},
                handler_id = #{handlerId},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{eventId}
              AND version = #{version}
            """)
    int updateStatusWithVersion(@Param("eventId") Long eventId,
                                @Param("toStatus") String toStatus,
                                @Param("handlerId") Long handlerId,
                                @Param("version") Integer version);

    /**
     * 关闭事件：同时写结果、说明与关闭时间，并推进版本。
     */
    @Update("""
            UPDATE alert_event
            SET status = 'CLOSED',
                close_result = #{closeResult},
                close_note = #{closeNote},
                closed_at = #{closedAt},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{eventId}
              AND version = #{version}
            """)
    int closeEvent(@Param("eventId") Long eventId,
                   @Param("closeResult") String closeResult,
                   @Param("closeNote") String closeNote,
                   @Param("closedAt") java.time.LocalDateTime closedAt,
                   @Param("version") Integer version);

    /**
     * 首次接收时间<b>只在为空时写入</b>。
     *
     * <p>用 {@code first_accepted_at IS NULL} 作为条件而不是先查后写：
     * 转派后由第二个人接收时，首次响应时间必须保持第一次的值。
     */
    @Update("""
            UPDATE alert_event
            SET first_accepted_at = #{acceptedAt},
                updated_at = NOW(3)
            WHERE id = #{eventId}
              AND first_accepted_at IS NULL
            """)
    int fillFirstAcceptedAt(@Param("eventId") Long eventId,
                            @Param("acceptedAt") java.time.LocalDateTime acceptedAt);

    /** 撤销事件 */
    @Update("""
            UPDATE alert_event
            SET status = 'CANCELLED',
                close_note = #{reason},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{eventId}
              AND version = #{version}
            """)
    int cancelEvent(@Param("eventId") Long eventId,
                    @Param("reason") String reason,
                    @Param("version") Integer version);

    /** 主动求助防重：按创建人 + requestId 查已存在事件 */
    @Select("""
            SELECT * FROM alert_event
            WHERE created_by = #{createdBy} AND request_id = #{requestId}
            """)
    AlertEvent findByCreatorAndRequest(@Param("createdBy") Long createdBy,
                                       @Param("requestId") String requestId);

    /** 管理员工作台：未结事件按创建时间倒序 */
    @Select("""
            SELECT * FROM alert_event
            WHERE status IN ('PENDING', 'ASSIGNED', 'PROCESSING')
            ORDER BY created_at DESC, id DESC
            """)
    List<AlertEvent> findAllOpen();

    /** 分派给某服务人员的未结事件 */
    @Select("""
            SELECT * FROM alert_event
            WHERE handler_id = #{handlerId}
              AND status IN ('PENDING', 'ASSIGNED', 'PROCESSING')
            ORDER BY created_at DESC, id DESC
            """)
    List<AlertEvent> findOpenByHandler(@Param("handlerId") Long handlerId);

    /** 某位老人自己的未结事件 */
    @Select("""
            SELECT * FROM alert_event
            WHERE elder_id = #{elderId}
              AND status IN ('PENDING', 'ASSIGNED', 'PROCESSING')
            ORDER BY created_at DESC, id DESC
            """)
    List<AlertEvent> findOpenByElder(@Param("elderId") Long elderId);

    /** 某位老人自己的全部事件（含已关闭） */
    @Select("SELECT * FROM alert_event WHERE elder_id = #{elderId} ORDER BY created_at DESC, id DESC")
    List<AlertEvent> findAllByElder(@Param("elderId") Long elderId);
}
