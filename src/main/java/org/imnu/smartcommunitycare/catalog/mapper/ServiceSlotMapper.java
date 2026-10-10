package org.imnu.smartcommunitycare.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务时段数据访问。
 */
@Mapper
public interface ServiceSlotMapper extends BaseMapper<ServiceSlot> {

    /**
     * 查与给定时间范围有重叠的时段。
     *
     * <p>重叠判定用 {@code start < :end AND end > :start}（半开区间），
     * 所以"前一个时段结束时正好等于本时段开始"不算重叠——那是正常的连续排班。
     */
    @Select("""
            SELECT * FROM service_slot
            WHERE service_id = #{serviceId}
              AND start_at < #{endAt}
              AND end_at > #{startAt}
            ORDER BY start_at
            """)
    List<ServiceSlot> findOverlapping(@Param("serviceId") Long serviceId,
                                      @Param("startAt") LocalDateTime startAt,
                                      @Param("endAt") LocalDateTime endAt);

    /** 某服务未来的开放时段，供老人浏览 */
    @Select("""
            SELECT * FROM service_slot
            WHERE service_id = #{serviceId}
              AND enabled = 1
              AND start_at > #{now}
            ORDER BY start_at
            """)
    List<ServiceSlot> findFutureOpen(@Param("serviceId") Long serviceId,
                                     @Param("now") LocalDateTime now);

    /** 锁定时段行。预约与取消都先锁它，保证名额增减不并发错乱 */
    @Select("SELECT * FROM service_slot WHERE id = #{id} FOR UPDATE")
    ServiceSlot findByIdForUpdate(@Param("id") Long id);

    /**
     * 占用一个名额（条件更新）。
     *
     * <p><b>扣名额与判断是否还有余量必须在同一条 SQL 里</b>：
     * 先 SELECT 查余量再 UPDATE，中间会被并发插入插进来，
     * 于是出现"容量 2 却建了 3 单"。影响行数为 0 即表示名额已满或时段已关闭。
     *
     * @return 影响行数，1 表示占用成功
     */
    @Update("""
            UPDATE service_slot
            SET occupied_count = occupied_count + 1,
                updated_at = NOW(3)
            WHERE id = #{slotId}
              AND enabled = 1
              AND start_at > #{now}
              AND occupied_count < capacity
            """)
    int occupySlot(@Param("slotId") Long slotId, @Param("now") LocalDateTime now);

    /**
     * 释放一个名额。
     *
     * <p>同样用条件更新而不是"先查再减"，避免重复取消导致释放两次。
     */
    @Update("""
            UPDATE service_slot
            SET occupied_count = occupied_count - 1,
                updated_at = NOW(3)
            WHERE id = #{slotId}
              AND occupied_count > 0
            """)
    int releaseSlot(@Param("slotId") Long slotId);

    /** 某服务下所有时段（管理员查看） */
    @Select("SELECT * FROM service_slot WHERE service_id = #{serviceId} ORDER BY start_at")
    List<ServiceSlot> findByService(@Param("serviceId") Long serviceId);
}
