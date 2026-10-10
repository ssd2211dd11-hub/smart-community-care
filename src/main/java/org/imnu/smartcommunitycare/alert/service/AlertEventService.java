package org.imnu.smartcommunitycare.alert.service;

import org.imnu.smartcommunitycare.alert.mapper.AlertEventLogMapper;
import org.imnu.smartcommunitycare.alert.mapper.AlertEventMapper;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEvent;
import org.imnu.smartcommunitycare.alert.model.entity.AlertEventLog;
import org.imnu.smartcommunitycare.alert.model.enums.AlertSourceType;
import org.imnu.smartcommunitycare.alert.model.enums.AlertStatus;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件创建与归并。
 *
 * <p><b>归并规则</b>（V1.2 §4.4.3）：同一老人、同一 {@code ruleCode}
 * 已存在未结事件时，新命中<b>追加</b>到该事件——加发生次数、写一条追加说明，
 * <b>不把状态改回待处理、不清空处理人、不顺延响应截止时间</b>。
 *
 * <p>理由：老人又测了一次、结果仍然异常，说明同一件事还在持续，
 * 不应该把已经认领的事项退回队列让处理人重新看一遍。
 */
@Service
@RequiredArgsConstructor
public class AlertEventService {

    private static final DateTimeFormatter EVENT_NO = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 事件号自增序号。
     *
     * <p>进程内计数：多实例部署时可能重复，因此事件号不作为唯一性保证，
     * 真正兜底的是数据库唯一约束。
     */
    private static final AtomicLong SEQ = new AtomicLong(0);

    private final AlertEventMapper eventMapper;
    private final AlertEventLogMapper logMapper;

    /** 首次响应时限（分钟），仅用于演示，不代表真实救援承诺 */
    @Value("${care.alert.response-due-minutes:120}")
    private long responseDueMinutes;

    private final Clock clock;

    /**
     * 健康规则命中：归并或创建事件。
     *
     * <p>整个过程在一个事务里，包含锁定读——并发提交同一规则时，
     * 只会产生一个未结事件，后到的追加证据。
     *
     * @param elderId  老人档案 ID
     * @param ruleCode 命中的规则编码
     * @param note     追加说明（归并时写入时间线）
     * @return 归并后的事件；新建时 occurrenceCount 为 1
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertEvent recordHealthHit(Long elderId, String ruleCode, String note) {
        // 锁定读：把"同一老人 + 同一规则"的并发串行化。
        // 用普通 SELECT 会出现两个请求都认为"没有未结事件"而各建一条。
        AlertEvent open = eventMapper.findOpenEventForUpdate(elderId, ruleCode);
        LocalDateTime now = LocalDateTime.now(clock);

        if (open != null) {
            // 追加证据：只加次数，不动状态/处理人/截止时间/首次响应时间
            eventMapper.appendOccurrence(open.getId());
            writeLog(open.getId(), "APPEND_EVIDENCE", open.getStatus(), open.getStatus(),
                    null, "SYSTEM", note);
            return eventMapper.findByIdForUpdate(open.getId());
        }

        AlertEvent created = new AlertEvent();
        created.setSourceType(AlertSourceType.HEALTH_ALERT.name());
        created.setRuleCode(ruleCode);
        created.setElderId(elderId);
        created.setStatus(AlertStatus.PENDING.name());
        created.setPriority("NORMAL");
        created.setOccurrenceCount(1);
        created.setResponseDueAt(now.plusMinutes(responseDueMinutes));
        created.setVersion(0);
        created.setDescription("健康指标命中待核实规则：" + ruleCode);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        // event_no 是 NOT NULL 且无默认值，必须在 insert 前赋值。
        // 事件号必须在插入前就有值——NOT NULL 列没有"稍后补"的余地。
        created.setEventNo(nextEventNo());
        eventMapper.insert(created);

        writeLog(created.getId(), "CREATE", null, AlertStatus.PENDING.name(),
                null, "SYSTEM", "规则命中生成");
        return created;
    }

    /**
     * 老人主动提交求助。
     *
     * <p>只按 requestId 防重：同一老人短时间内再次求助是<b>两次独立事件</b>，
     * 不按"五分钟内同一老人"吞掉——人可能真的需要两次帮助。
     */
    @Transactional(rollbackFor = Exception.class)
    public AlertEvent createHelpCall(Long elderId, String callType, String contactPhone,
                                     String description, String requestId, String requestHash,
                                     Long createdBy) {
        if (requestId == null || requestId.isBlank()) {
            throw BusinessException.badRequest("提交求助必须携带 requestId");
        }
        AlertEvent existing = eventMapper.findByCreatorAndRequest(createdBy, requestId);
        if (existing != null) {
            if (requestHash != null && !requestHash.equals(existing.getRequestHash())) {
                throw BusinessException.conflict("相同请求标识提交了不同内容");
            }
            // 网络重试：返回原事件，不重复创建
            return existing;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        AlertEvent event = new AlertEvent();
        event.setSourceType(AlertSourceType.HELP_CALL.name());
        event.setElderId(elderId);
        event.setStatus(AlertStatus.PENDING.name());
        // 主动求助默认高优先级：它反映的是"人现在需要帮助"
        event.setPriority("HIGH");
        event.setOccurrenceCount(1);
        event.setResponseDueAt(now.plusMinutes(responseDueMinutes));
        event.setCallType(callType);
        event.setContactPhone(contactPhone);
        event.setDescription(description);
        event.setRequestId(requestId);
        event.setRequestHash(requestHash);
        event.setVersion(0);
        event.setCreatedBy(createdBy);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        event.setEventNo(nextEventNo());
        eventMapper.insert(event);

        writeLog(event.getId(), "CREATE", null, AlertStatus.PENDING.name(),
                createdBy, "USER", "老人提交求助");
        return event;
    }

    /** 追加一条处理记录 */
    private void writeLog(Long eventId, String action, String fromStatus, String toStatus,
                          Long operatorId, String operatorSource, String reason) {
        AlertEventLog log = new AlertEventLog();
        log.setEventId(eventId);
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperatorId(operatorId);
        log.setOperatorSource(operatorSource);
        log.setReason(reason);
        log.setCreatedAt(LocalDateTime.now(clock));
        logMapper.insert(log);
    }

    /**
     * 生成下一个事件号：AE + 时间戳 + 自增序号 + 随机位。
     *
     * <p>三段拼接各有分工：时间戳让人能看出大致发生时间；
     * 自增序号解决同一毫秒内多次创建的碰撞；随机位兜住进程重启后计数归零的情况。
     * 真正的唯一性仍由 {@code uk_alert_event_no} 保证，撞了就重试。
     */
    private String nextEventNo() {
        long seq = SEQ.incrementAndGet() % 1000;
        int rand = ThreadLocalRandom.current().nextInt(100, 1000);
        return "AE" + LocalDateTime.now(clock).format(EVENT_NO)
                + String.format("%03d", seq) + rand;
    }

    /** 读取某事件的时间线 */
    public List<AlertEventLog> timeline(Long eventId) {
        return logMapper.findByEventId(eventId);
    }

    /**
     * 判断事件是否首次响应逾期。
     *
     * <p>条件：<b>尚未关闭/撤销、从未被接收，且当前时间超过截止时间</b>。
     * 用计算而不是定时任务写字段——不写就不怕定时器漏跑，也不会出现
     * "任务没跑所以没人逾期"的错觉。
     */
    public boolean isFirstResponseOverdue(AlertEvent event) {
        if (event.isOpen() && event.getFirstAcceptedAt() == null
                && event.getResponseDueAt() != null) {
            return LocalDateTime.now(clock).isAfter(event.getResponseDueAt());
        }
        return false;
    }
}
