package org.imnu.smartcommunitycare.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.device.model.entity.DeviceCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 模拟控制指令数据访问。
 */
@Mapper
public interface DeviceCommandMapper extends BaseMapper<DeviceCommand> {

    /**
     * 按防重键查指令。
     *
     * <p>唯一键是 {@code (requested_by, request_id)}：同一次提交重试沿用同一个
     * requestId，不会重复下发；用户明确再次操作才换新的 requestId。
     */
    @Select("""
            SELECT * FROM device_command
            WHERE requested_by = #{requestedBy} AND request_id = #{requestId}
            """)
    DeviceCommand findByRequest(@Param("requestedBy") Long requestedBy,
                                @Param("requestId") String requestId);

    @Select("SELECT * FROM device_command WHERE id = #{id}")
    DeviceCommand findById(@Param("id") Long id);

    /** 锁定指令行。执行前先锁它，避免两条指令并发改同一行 */
    @Select("SELECT * FROM device_command WHERE id = #{id} FOR UPDATE")
    DeviceCommand findByIdForUpdate(@Param("id") Long id);

    /** 某设备当前仍在有效期内的待处理指令 */
    @Select("""
            SELECT * FROM device_command
            WHERE device_id = #{deviceId} AND status = 'PENDING'
            ORDER BY id
            LIMIT 1
            """)
    DeviceCommand findPending(@Param("deviceId") Long deviceId);

    /** 某设备的指令历史，按创建时间倒序 */
    @Select("""
            SELECT * FROM device_command
            WHERE device_id = #{deviceId}
            ORDER BY created_at DESC, id DESC
            """)
    List<DeviceCommand> findByDevice(@Param("deviceId") Long deviceId);

    /**
     * 收口过期指令：把已到期仍为 PENDING 的落为 TIMED_OUT。
     *
     * <p>TIMED_OUT 表示结果未知，不表示失败——设备可能执行了只是没回报。
     * 返回影响行数，调用方可据此判断是否需要写终态日志。
     */
    @Update("""
            UPDATE device_command
            SET status = 'TIMED_OUT',
                updated_at = #{now}
            WHERE device_id = #{deviceId}
              AND status = 'PENDING'
              AND expires_at <= #{now}
            """)
    int timeoutExpired(@Param("deviceId") Long deviceId, @Param("now") LocalDateTime now);

    /**
     * 执行成功：落终态并记录虚拟开关结果。
     *
     * <p>同时写 {@code simulated_executed_at}，防止同一条指令被重复执行。
     */
    @Update("""
            UPDATE device_command
            SET status = 'SUCCEEDED',
                result_power_status = #{resultStatus},
                acknowledged_at = #{now},
                simulated_executed_at = #{now},
                updated_at = #{now}
            WHERE id = #{commandId} AND status = 'PENDING'
            """)
    int markSucceeded(@Param("commandId") Long commandId,
                      @Param("resultStatus") String resultStatus,
                      @Param("now") LocalDateTime now);

    /** 执行失败：开关不变，指令落 FAILED */
    @Update("""
            UPDATE device_command
            SET status = 'FAILED',
                reason = #{reason},
                acknowledged_at = #{now},
                simulated_executed_at = #{now},
                updated_at = #{now}
            WHERE id = #{commandId} AND status = 'PENDING'
            """)
    int markFailed(@Param("commandId") Long commandId,
                   @Param("reason") String reason,
                   @Param("now") LocalDateTime now);

    /**
     * NO_ACK：设备已改变开关但不确认结果。
     *
     * <p>只写 {@code simulated_executed_at} 与设备侧状态，指令保持 PENDING，
     * 到期后由 {@link #timeoutExpired} 收口。这样每轮调度不会重复执行它，
     * 页面看到的正是「设备已变、结果未知」。
     */
    @Update("""
            UPDATE device_command
            SET simulated_executed_at = #{now},
                updated_at = #{now}
            WHERE id = #{commandId} AND status = 'PENDING'
            """)
    int markExecutedWithoutAck(@Param("commandId") Long commandId,
                               @Param("now") LocalDateTime now);

    /**
     * 设备停用或归属变更时，使尚有效的待处理指令失效。
     *
     * <p>已被模拟器取走的指令无法保证瞬间撤回；晚到的结果按状态条件
     * 更新会因状态已非 PENDING 而影响 0 行，自然被拒。
     */
    @Update("""
            UPDATE device_command
            SET status = 'CANCELLED',
                reason = #{reason},
                updated_at = #{now}
            WHERE device_id = #{deviceId}
              AND status = 'PENDING'
              AND expires_at > #{now}
              AND simulated_executed_at IS NULL
            """)
    int cancelPending(@Param("deviceId") Long deviceId,
                      @Param("reason") String reason,
                      @Param("now") LocalDateTime now);
}
