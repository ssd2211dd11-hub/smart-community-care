package org.imnu.smartcommunitycare.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrderLog;
import org.imnu.smartcommunitycare.order.model.response.MyParticipationResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单处理记录数据访问。
 *
 * <p>只追加不修改：日志是时间线的事实来源，状态流转与日志写入在同一事务里完成。
 */
@Mapper
public interface ServiceOrderLogMapper extends BaseMapper<ServiceOrderLog> {

    /** 某订单的完整处理时间线 */
    @Select("""
            SELECT * FROM service_order_log
            WHERE order_id = #{orderId}
            ORDER BY created_at, id
            """)
    List<ServiceOrderLog> findByOrder(@Param("orderId") Long orderId);

    /**
     * 依据operator_id汇总本人执行过的领取、接单、拒接、开工和提交结果动作。
     * 转派后保留参与事实；SQL仅选择脱敏摘要，不返回电话或地址。
     * 每个订单一行，GROUP_CONCAT汇总本人动作；未执行动作的仅受派记录不计入。
     */
    @Select("""
            <script>
            SELECT
              CAST(o.id AS CHAR)                AS orderId,
              o.order_no                        AS orderNo,
              o.service_name_snapshot           AS serviceNameSnapshot,
              o.slot_start_snapshot             AS slotStartSnapshot,
              o.slot_end_snapshot               AS slotEndSnapshot,
              u.real_name                       AS elderName,
              GROUP_CONCAT(l.action ORDER BY l.created_at SEPARATOR ' / ') AS myActions,
              MAX(l.created_at)                 AS lastActionAt,
              o.status                          AS finalStatus
            FROM service_order_log l
            JOIN service_order o ON o.id = l.order_id
            LEFT JOIN elder_profile p ON p.id = o.elder_id
            LEFT JOIN sys_user u ON u.id = p.user_id
            WHERE l.operator_id = #{staffId}
              AND l.action IN ('CLAIM', 'ACCEPT', 'REJECT', 'START', 'SUBMIT')
            GROUP BY o.id, o.order_no, o.service_name_snapshot,
                     o.slot_start_snapshot, o.slot_end_snapshot,
                     u.real_name, o.status
            ORDER BY lastActionAt DESC
            LIMIT #{limit}
            </script>
            """)
    List<MyParticipationResponse> findMyParticipations(@Param("staffId") Long staffId,
                                                       @Param("limit") int limit);
    @Select("""
            SELECT CAST(o.id AS CHAR) AS orderId,o.order_no AS orderNo,o.service_name_snapshot AS serviceNameSnapshot,
                   o.slot_start_snapshot AS slotStartSnapshot,o.slot_end_snapshot AS slotEndSnapshot,u.real_name AS elderName,
                   GROUP_CONCAT(l.action ORDER BY l.created_at,l.id SEPARATOR ' / ') AS myActions,
                   MAX(l.created_at) AS lastActionAt,o.status AS finalStatus
            FROM service_order_log l JOIN service_order o ON o.id=l.order_id
            LEFT JOIN elder_profile p ON p.id=o.elder_id LEFT JOIN sys_user u ON u.id=p.user_id
            WHERE l.operator_id=#{staffId} AND l.action IN('CLAIM','ACCEPT','REJECT','START','SUBMIT')
            GROUP BY o.id,o.order_no,o.service_name_snapshot,o.slot_start_snapshot,o.slot_end_snapshot,u.real_name,o.status
            ORDER BY lastActionAt DESC,o.id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<MyParticipationResponse> participationPage(@Param("staffId") Long staffId,@Param("limit") int limit,@Param("offset") long offset);
    @Select("SELECT COUNT(DISTINCT order_id) FROM service_order_log WHERE operator_id=#{staffId} AND action IN('CLAIM','ACCEPT','REJECT','START','SUBMIT')")
    long participationCount(@Param("staffId") Long staffId);
}
