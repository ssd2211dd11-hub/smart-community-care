package org.imnu.smartcommunitycare.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrderLog;
import org.imnu.smartcommunitycare.order.model.response.ClaimableOrderResponse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 服务订单数据访问。
 *
 * <p>名额增减不在这里做——那属于时段的职责，由 {@code ServiceSlotMapper} 的条件更新完成。
 * 本 Mapper 只处理订单自身的数据。
 */
@Mapper
public interface ServiceOrderMapper extends BaseMapper<ServiceOrder> {

    @Select("""
            SELECT CAST(id AS CHAR) AS id, order_no, service_name_snapshot, price_snapshot,
                   slot_start_snapshot, slot_end_snapshot, version
            FROM service_order WHERE status='PENDING_ASSIGNMENT' AND staff_id IS NULL
            ORDER BY slot_start_snapshot, id LIMIT #{limit} OFFSET #{offset}
            """)
    List<ClaimableOrderResponse> claimablePage(
            @Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM service_order WHERE status='PENDING_ASSIGNMENT' AND staff_id IS NULL")
    long countClaimable();

    @Update("""
            UPDATE service_order SET staff_id=#{staffId}, status='ACCEPTED', accepted_at=#{now},
                version=version+1, updated_at=#{now}
            WHERE id=#{orderId} AND status='PENDING_ASSIGNMENT' AND staff_id IS NULL AND version=#{version}
            """)
    int claimByStaff(@Param("orderId") Long orderId, @Param("staffId") Long staffId,
                     @Param("version") Integer version, @Param("now") java.time.LocalDateTime now);

    /**
     * 按防重键查已有订单。
     *
     * <p>唯一键是 {@code (elder_id, request_id)}：同一次提交重试沿用同一个 requestId，
     *不会重复建单；用户明确再次预约才会换新的 requestId。
     *
     * <p><b>这里刻意用普通 SELECT 而非 FOR UPDATE。</b>
     * 订单写入的加锁顺序是 {@code service_item → service_slot → service_order}。
     * 若防重查询先锁 {@code service_order}，就形成「先订单后时段」的顺序，
     * 与其他事务（先时段后订单）相反，同键并发时直接死锁。
     *
     * <p>并发同键的兜底是唯一约束 {@code uk_service_order_creator_request}：
     * 并发请求各自扣名额后只有一个 insert 成功，其余撞唯一键；
     * 由 {@code OrderBookingService} 捕获 {@code DuplicateKeyException}，
     * 在失败事务回滚后于新事务回查该订单并返回。
     */
    @Select("""
            SELECT * FROM service_order
            WHERE elder_id = #{elderId}
              AND request_id = #{requestId}
            """)
    ServiceOrder findByRequest(@Param("elderId") Long elderId,
                               @Param("requestId") String requestId);

    /** 锁定订单行。状态流转前先锁它，避免两人同时操作同一订单 */
    @Select("SELECT * FROM service_order WHERE id = #{id} FOR UPDATE")
    ServiceOrder findByIdForUpdate(@Param("id") Long id);

    /** 某老人的订单，按创建时间倒序 */
    @Select("""
            SELECT * FROM service_order
            WHERE elder_id = #{elderId}
            ORDER BY created_at DESC, id DESC
            """)
    List<ServiceOrder> findByElder(@Param("elderId") Long elderId);

    /** 某服务人员当前的订单 */
    @Select("""
            SELECT * FROM service_order
            WHERE staff_id = #{staffId}
            ORDER BY created_at DESC, id DESC
            """)
    List<ServiceOrder> findByStaff(@Param("staffId") Long staffId);

    // ---------- 本人列表的服务端分页 ----------

    /**
     * 按当前老人查询分页订单，归属条件由会话提供。
     * 保留原全量查询契约；created_at DESC、id DESC保证排序稳定。
     */
    @Select("""
            <script>
            SELECT * FROM service_order
            <where>
              elder_id = #{elderId}
              <if test="status != null and status != ''">AND status = #{status}</if>
            </where>
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ServiceOrder> pageByElder(@Param("elderId") Long elderId,
                                   @Param("status") String status,
                                   @Param("limit") int limit,
                                   @Param("offset") long offset);

    /** 老人本人订单总数，筛选条件与 {@link #pageByElder} 一致 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM service_order
            <where>
              elder_id = #{elderId}
              <if test="status != null and status != ''">AND status = #{status}</if>
            </where>
            </script>
            """)
    long countByElder(@Param("elderId") Long elderId,
                      @Param("status") String status);

    /**
     * 服务人员本人任务，<b>服务端分页</b>，支持按状态筛选。
     *
     * <p>与 {@link #pageByElder} 同构：{@code staff_id} 取自会话，
     * 翻页换不掉归属；排序同样用 {@code id} 兜底保证稳定。
     */
    @Select("""
            <script>
            SELECT * FROM service_order
            <where>
              staff_id = #{staffId}
              <if test="status != null and status != ''">AND status = #{status}</if>
            </where>
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ServiceOrder> pageByStaff(@Param("staffId") Long staffId,
                                   @Param("status") String status,
                                   @Param("limit") int limit,
                                   @Param("offset") long offset);

    /** 服务人员本人任务总数，筛选条件与 {@link #pageByStaff} 一致 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM service_order
            <where>
              staff_id = #{staffId}
              <if test="status != null and status != ''">AND status = #{status}</if>
            </where>
            </script>
            """)
    long countByStaff(@Param("staffId") Long staffId,
                      @Param("status") String status);

    /**
     * 管理员视角的订单列表，按创建时间倒序，支持按状态筛选。
     *
     * <p>带 limit/offset 的服务端分页：订单会持续增长，
     * 一次把全表拉出去让浏览器过滤既慢，也会在数据增长后撑爆页面。
     *
     * @param status 状态筛选，null 或空串表示不过滤
     */
    @Select("""
            <script>
            SELECT * FROM service_order
            <where>
              <if test="status != null and status != ''">status = #{status}</if>
            </where>
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ServiceOrder> pageForAdmin(@Param("status") String status,
                                     @Param("limit") int limit,
                                     @Param("offset") long offset);

    /** 管理员视角的订单总数，筛选条件与 {@link #pageForAdmin} 保持一致 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM service_order
            <where>
              <if test="status != null and status != ''">status = #{status}</if>
            </where>
            </script>
            """)
    long countForAdmin(@Param("status") String status);

    /**
     * 某订单的完整处理时间线。
     */
    @org.apache.ibatis.annotations.Select("""
            SELECT * FROM service_order_log
            WHERE order_id = #{orderId}
            ORDER BY created_at, id
            """)
    List<ServiceOrderLog> findLogsByOrder(@Param("orderId") Long orderId);

    /**
     * 按状态条件流转，同时校验乐观锁版本。
     *
     * <p>把「当前状态」和「版本号」都写进 WHERE，而不是先查出来再无条件 update：
     * 两人同时点「接单」时，只有一个人的状态与版本仍然匹配，另一个影响行数为 0，
     * 业务层据此返回冲突，而不是把状态覆盖掉。
     *
     * @return 影响行数，1 表示流转成功
     */
    @Update("""
            UPDATE service_order
            SET status = #{toStatus},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND status = #{fromStatus}
              AND version = #{version}
            """)
    int compareAndSetStatus(@Param("orderId") Long orderId,
                            @Param("fromStatus") String fromStatus,
                            @Param("toStatus") String toStatus,
                            @Param("version") Integer version);

    /**
     * 分派：设置受派人员，进入待接单。
     *
     * <p>与 {@link #compareAndSetStatus} 分开而不是加一堆可空参数：
     * 各动作要写的字段不同，用一个通用方法靠if拼SQL 会让"这个动作到底改了
     * 什么字段"变得不可读。这里每个动作一条，字段一目了然。
     *
     * @param staffId 受派服务人员账号
     * @return 影响行数，1 表示成功
     */
    @Update("""
            UPDATE service_order
            SET status = 'ASSIGNED',
                staff_id = #{staffId},
                accepted_at = NULL,
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND status = 'PENDING_ASSIGNMENT'
              AND version = #{version}
            """)
    int assignStaff(@Param("orderId") Long orderId,
                    @Param("staffId") Long staffId,
                    @Param("version") Integer version);

    /**
     * 接单：当前受派人确认，进入待服务。
     *
     * <p>同时校验「当前登录人就是受派人」，把归属判断放到SQL 条件里，
     * 避免依赖先查后改的时序。
     */
    @Update("""
            UPDATE service_order
            SET status = 'ACCEPTED',
                accepted_at = #{now},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND status = 'ASSIGNED'
              AND version = #{version}
              AND staff_id = #{staffId}
            """)
    int acceptByStaff(@Param("orderId") Long orderId,
                      @Param("staffId") Long staffId,
                      @Param("version") Integer version,
                      @Param("now") java.time.LocalDateTime now);

    /**
     * 拒接：回到待分派，<b>清空受派人与本轮接单时间</b>。
     *
     * <p>必须显式写 NULL。用 Java 对象置null 再 update 不会生效——
     * MyBatis-Plus 默认策略会跳过 null 字段，受派人会残留在订单上，
     * 下一个被分派的人接手时看到「已有接单时间」。
     */
    @Update("""
            UPDATE service_order
            SET status = 'PENDING_ASSIGNMENT',
                staff_id = NULL,
                accepted_at = NULL,
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND status = 'ASSIGNED'
              AND version = #{version}
              AND staff_id = #{staffId}
            """)
    int rejectByStaff(@Param("orderId") Long orderId,
                      @Param("staffId") Long staffId,
                      @Param("version") Integer version);

    /**
     * 转派：换人，<b>清空本轮接单时间</b>。
     *
     * <p>新受派人需要重新接单——转派不是「已接受」，不能让新人和
     * 上一任共享同一个 accept 时间。历史受派与原因保留在日志里。
     *
     * <p>允许从 ASSIGNED 或 ACCEPTED 转出，对应状态机里的两条规则。
     */
    @Update("""
            UPDATE service_order
            SET status = 'ASSIGNED',
                staff_id = #{newStaffId},
                accepted_at = NULL,
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND version = #{version}
              AND status IN ('ASSIGNED', 'ACCEPTED')
            """)
    int transferStaff(@Param("orderId") Long orderId,
                      @Param("newStaffId") Long newStaffId,
                      @Param("version") Integer version);

    /**
     * 开始服务：记录首次开工时刻，进入服务中。
     *
     * <p>{@code started_at IS NULL} 作为附加条件，保证<b>首次开工</b>不会被覆盖：
     * 服务中如果发生退回重做，再次开工不应把首次时间冲掉——
     * 那是已经发生过的过程事实。
     *
     * <p>开始时间不早于预约开始，由 Service 层校验后调用。
     */
    @Update("""
            UPDATE service_order
            SET status = 'IN_SERVICE',
                started_at = COALESCE(started_at, #{now}),
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND status = 'ACCEPTED'
              AND version = #{version}
              AND staff_id = #{staffId}
            """)
    int startService(@Param("orderId") Long orderId,
                     @Param("staffId") Long staffId,
                     @Param("version") Integer version,
                     @Param("now") java.time.LocalDateTime now);

    /**
     * 提交服务结果：进入待确认。
     *
     * <p>{@code finished_at} 每次提交都刷新为最新时刻，但 {@code started_at} 不动——
     * 前者表示「这一轮做完的时间」，后者是「首次开工」，两者语义不同。
     */
    @Update("""
            UPDATE service_order
            SET status = 'PENDING_CONFIRMATION',
                service_result = #{result},
                finished_at = #{now},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND status = 'IN_SERVICE'
              AND version = #{version}
              AND staff_id = #{staffId}
            """)
    int submitResult(@Param("orderId") Long orderId,
                     @Param("staffId") Long staffId,
                     @Param("version") Integer version,
                     @Param("result") String result,
                     @Param("now") java.time.LocalDateTime now);

    /**
     * 老人确认完成：进入终态。
     *
     * <p>{@code elderId} 写进条件，确保只有订单所属老人能确认自己的订单。
     *
     * <p>管理员代确认走 {@link #confirmByAdmin}，那里要求填写原因。
     */
    @Update("""
            UPDATE service_order
            SET status = 'COMPLETED',
                confirmed_at = #{now},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND status = 'PENDING_CONFIRMATION'
              AND version = #{version}
              AND elder_id = #{elderId}
            """)
    int confirmByElder(@Param("orderId") Long orderId,
                       @Param("elderId") Long elderId,
                       @Param("version") Integer version,
                       @Param("now") java.time.LocalDateTime now);

    /**
     * 管理员代确认：与老人确认同样进入终态，但日志需记录代确认原因。
     */
    @Update("""
            UPDATE service_order
            SET status = 'COMPLETED',
                confirmed_at = #{now},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND status = 'PENDING_CONFIRMATION'
              AND version = #{version}
            """)
    int confirmByAdmin(@Param("orderId") Long orderId,
                       @Param("version") Integer version,
                       @Param("now") java.time.LocalDateTime now);

    /**
     * 退回重做：回到服务中。
     *
     * <p><b>不重置 started_at 与 finished_at</b>：退回不是「从未开始」，
     * 保留首次开工时间才能反映真实过程。日志里记录每次退回原因，
     * 历次提交与退回都留痕，不用最新结果覆盖全部历史。
     */
    @Update("""
            UPDATE service_order
            SET status = 'IN_SERVICE',
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND status = 'PENDING_CONFIRMATION'
              AND version = #{version}
            """)
    int returnToService(@Param("orderId") Long orderId,
                        @Param("version") Integer version);

    /**
     * 异常终止：管理员核实后终止，不算成功完成。
     *
     * <p>允许从服务中或待确认进入。已开工的单不能走取消释放名额，
     * 只能走这里——服务已经发生，名额算用过。
     */
    @Update("""
            UPDATE service_order
            SET status = 'TERMINATED',
                terminated_at = #{now},
                version = version + 1,
                updated_at = NOW(3)
            WHERE id = #{orderId}
              AND version = #{version}
              AND status IN ('IN_SERVICE', 'PENDING_CONFIRMATION')
            """)
    int terminateOrder(@Param("orderId") Long orderId,
                       @Param("version") Integer version,
                       @Param("now") java.time.LocalDateTime now);
}
