package org.imnu.smartcommunitycare.health.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecordAnomaly;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 规则命中明细数据访问。
 */
@Mapper
public interface HealthRecordAnomalyMapper extends BaseMapper<HealthRecordAnomaly> {

    /** 某条记录的全部命中明细 */
    @Select("SELECT * FROM health_record_anomaly WHERE health_record_id = #{recordId}")
    List<HealthRecordAnomaly> findByRecordId(@Param("recordId") Long recordId);

    /** 某条记录是否命中了指定规则（归并判定用） */
    @Select("""
            SELECT COUNT(*) FROM health_record_anomaly
            WHERE health_record_id = #{recordId} AND rule_code = #{ruleCode}
            """)
    int countByRecordAndRule(@Param("recordId") Long recordId,
                             @Param("ruleCode") String ruleCode);
}
