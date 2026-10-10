package org.imnu.smartcommunitycare.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.device.model.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备数据访问。
 */
@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    @Select("SELECT * FROM device WHERE device_no = #{deviceNo}")
    Device findByNo(@Param("deviceNo") String deviceNo);

    /**
     * 锁定设备行。
     *
     * <p>控制指令与模拟执行都要先锁它，保证同一设备的并发操作串行化。
     * 锁顺序为 device → device_command，与订单模块的约定同源。
     */
    @Select("SELECT * FROM device WHERE id = #{id} FOR UPDATE")
    Device findByIdForUpdate(@Param("id") Long id);

    /**
     * 调度候选：正在运行的设备，或仍有待处理指令的设备。
     *
     * <p>后者不能漏——模拟器停止后，之前已提交但未执行的指令仍需被
     * 收口为 TIMED_OUT，否则会永远停在 PENDING。
     */
    @Select("""
            <script>
            SELECT d.* FROM device d
            WHERE d.id &gt; #{afterId}
              AND ((d.enabled = 1 AND d.simulator_running = 1)
                   OR EXISTS (SELECT 1 FROM device_command c
                              WHERE c.device_id = d.id AND c.status = 'PENDING'))
            ORDER BY d.id
            LIMIT #{limit}
            </script>
            """)
    List<Device> findSchedulerCandidates(@Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * 写一次模拟心跳：把虚拟设备当前状态作为上报状态，并记录时间。
     *
     * <p>{@code last_seen_at} 由后端写入，绝不接受客户端提供的时间。
     */
    @Update("""
            UPDATE device
            SET power_status = #{reportedStatus},
                last_seen_at = #{now},
                updated_at = #{now}
            WHERE id = #{deviceId}
            """)
    int writeHeartbeat(@Param("deviceId") Long deviceId,
                       @Param("reportedStatus") String reportedStatus,
                       @Param("now") LocalDateTime now);

    /** 更新模拟器自身状态（不涉及上报） */
    @Update("""
            UPDATE device
            SET simulator_running = #{running},
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{deviceId}
            """)
    int updateSimulatorRunning(@Param("deviceId") Long deviceId,
                               @Param("running") boolean running,
                               @Param("now") LocalDateTime now);

    /** 切换执行场景 */
    @Update("""
            UPDATE device
            SET simulation_mode = #{mode},
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{deviceId}
            """)
    int updateSimulationMode(@Param("deviceId") Long deviceId,
                             @Param("mode") String mode,
                             @Param("now") LocalDateTime now);

    /**
     * 更新虚拟设备自身的开关状态。
     *
     * <p>与 {@code power_status} 分开：这里改的是「设备实际状态」，
     * 上报要等心跳周期，心跳没来之前两者会不一致——
     * 这正是「指令已执行但结果未确认」要展示的样子。
     */
    @Update("""
            UPDATE device
            SET simulated_power_status = #{status},
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{deviceId}
            """)
    int updateSimulatedPowerStatus(@Param("deviceId") Long deviceId,
                                   @Param("status") String status,
                                   @Param("now") LocalDateTime now);

    /** 业务停用或启用 */
    @Update("""
            UPDATE device
            SET enabled = #{enabled},
                version = version + 1,
                updated_at = #{now}
            WHERE id = #{deviceId}
            """)
    int updateEnabled(@Param("deviceId") Long deviceId,
                      @Param("enabled") boolean enabled,
                      @Param("now") LocalDateTime now);
}
