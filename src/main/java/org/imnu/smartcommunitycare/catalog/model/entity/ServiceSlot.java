package org.imnu.smartcommunitycare.catalog.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * 服务预约时段。
 *
 * <p><b>容量语义</b>：{@code capacity} 是该时段可接受的预约数，
 * <b>不等于已完成服务的人员排班</b>。预约到开始时间自动停止受理，
 * 用查询时的时间条件判断，不需要定时任务改状态。
 *
 * <p>不变量由数据库 CHECK 兜底：{@code 0 <= occupied_count <= capacity}、
 * {@code end_at > start_at}、{@code capacity > 0}。
 */
@Getter
@Setter
@TableName("service_slot")
public class ServiceSlot {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long serviceId;

    /** 预约开始时间，创建时必须在未来 */
    private LocalDateTime startAt;

    private LocalDateTime endAt;

    /** 可接受的预约数 */
    private Integer capacity;

    /** 已占用名额 */
    private Integer occupiedCount;

    /** 关闭后不受理新预约 */
    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public int remaining() {
        int remain = capacity - occupiedCount;
        return Math.max(remain, 0);
    }
}
