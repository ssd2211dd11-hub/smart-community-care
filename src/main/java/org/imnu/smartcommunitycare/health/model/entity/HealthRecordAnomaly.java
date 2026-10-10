package org.imnu.smartcommunitycare.health.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 规则命中明细。
 *
 * <p><b>存快照而不是只存一个"异常等级"</b>：把当时的数值、上下界、单位、
 * 规则版本都留下来。规则改了以后，旧记录仍能还原"当时为什么判为异常"，
 * 不会因为配置变化而被追溯性地改写。
 */
@Getter
@Setter
@TableName("health_record_anomaly")
public class HealthRecordAnomaly {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long healthRecordId;

    private String ruleCode;

    /** 规则版本快照。规则升级不覆盖旧证据，靠这个字段区分 */
    private String ruleVersion;

    /** heartRate / systolic / ... */
    private String metric;

    private BigDecimal measuredValue;
    private BigDecimal lowerBound;
    private BigDecimal upperBound;
    private String unit;

    private LocalDateTime hitAt;

    /** 关联的当前告警事件；历史补录与作废记录可为空 */
    private Long eventId;
}
