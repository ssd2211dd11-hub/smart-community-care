package org.imnu.smartcommunitycare.health.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 健康体征记录实体。
 *
 * <p>{@code measuredAt} 与 {@code createdAt} 分开：前者是测量发生的时间，
 * 后者是这条记录写进库的时间。历史补录时两者相差很远，
 * 混用会导致"三天前测的数据看起来是刚测的"。
 */
@Getter
@Setter
@TableName("health_record")
public class HealthRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 老人档案主键，不是账号 ID */
    private Long elderId;

    private Integer heartRate;
    private BigDecimal systolic;
    private BigDecimal diastolic;
    private BigDecimal bloodSugar;
    private BigDecimal bodyTemperature;
    private BigDecimal sleepHours;

    /** FASTING / POSTPRANDIAL / RANDOM / UNKNOWN */
    private String measureScene;

    /** 测量时间：CURRENT 取保存时刻，HISTORY 由用户填写 */
    private LocalDateTime measuredAt;

    /** CURRENT / HISTORY */
    private String mode;

    /** MANUAL / DEMO */
    private String source;

    private Long sourceDeviceId;
    private LocalDateTime sourceCollectedAt;

    /** 作废后不参与概览，但已发生的处置记录保留 */
    private Boolean voided;
    private String voidReason;
    private Long voidedBy;
    private LocalDateTime voidedAt;

    /** 录入人账号；设备自动采集时为空 */
    private Long createdBy;

    private String requestId;
    private String requestHash;

    private LocalDateTime createdAt;

    /** 取出某个指标的值，规则判定按指标编码取用 */
    public BigDecimal valueOf(String metricCode) {
        return switch (metricCode) {
            case "heartRate" -> heartRate == null ? null : BigDecimal.valueOf(heartRate);
            case "systolic" -> systolic;
            case "diastolic" -> diastolic;
            case "bloodSugar" -> bloodSugar;
            case "bodyTemperature" -> bodyTemperature;
            case "sleepHours" -> sleepHours;
            default -> null;
        };
    }
}
