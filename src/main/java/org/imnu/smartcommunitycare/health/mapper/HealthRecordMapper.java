package org.imnu.smartcommunitycare.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 健康记录数据访问。
 *
 * <p>七日查询用<b>真实时间范围</b>（measured_at BETWEEN），不用 {@code LIMIT 7}——
 * 取最近 7 条不等于最近 7 天，一天录多次时趋势图只剩两天。
 */
@Mapper
public interface HealthRecordMapper extends BaseMapper<HealthRecord> {

    /**
     * 按自然日范围查询有效记录。
     *
     * <p>范围为闭区间 [start, end]，与"今天及前 6 个自然日"口径一致。
     * 作废记录不返回。
     */
    @Select("""
            SELECT * FROM health_record
            WHERE elder_id = #{elderId}
              AND voided = 0
              AND measured_at >= #{start}
              AND measured_at < #{end}
            ORDER BY measured_at ASC, id ASC
            """)
    List<HealthRecord> findInRange(@Param("elderId") Long elderId,
                                  @Param("start") LocalDateTime start,
                                  @Param("end") LocalDateTime end);

    /** 最近一条有效记录（按实际测量时间） */
    @Select("""
            SELECT * FROM health_record
            WHERE elder_id = #{elderId} AND voided = 0
            ORDER BY measured_at DESC, id DESC
            LIMIT 1
            """)
    HealthRecord findLatest(@Param("elderId") Long elderId);

    /** 按录入人+requestId 查已存在记录，用于创建防重 */
    @Select("""
            SELECT * FROM health_record
            WHERE created_by = #{createdBy} AND request_id = #{requestId}
            """)
    HealthRecord findByCreatorAndRequest(@Param("createdBy") Long createdBy,
                                         @Param("requestId") String requestId);
}
