package org.imnu.smartcommunitycare.health.service;

import org.imnu.smartcommunitycare.alert.model.entity.AlertEvent;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.alert.service.AlertEventService;
import org.imnu.smartcommunitycare.elder.service.ElderProfileService;
import org.imnu.smartcommunitycare.health.mapper.HealthRecordAnomalyMapper;
import org.imnu.smartcommunitycare.health.mapper.HealthRecordMapper;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecord;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecordAnomaly;
import org.imnu.smartcommunitycare.health.model.enums.HealthRule;
import org.imnu.smartcommunitycare.health.model.enums.RecordMode;
import org.imnu.smartcommunitycare.health.model.request.CreateHealthRecordRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 健康记录服务。
 *
 * <p>录入一次要做四件事，缺一不可：
 * <ol>
 *   <li><b>防重</b>：同一次提交重试不产生两条记录</li>
 *   <li><b>规则判定</b>：保存命中项的数值与阈值快照</li>
 *   <li><b>区分当前与补录</b>：只有当前测量才产生待办</li>
 *   <li><b>记录操作人</b>：管理员代录要留痕</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class HealthRecordService {

    private final HealthRecordMapper healthRecordMapper;
    private final HealthRecordAnomalyMapper anomalyMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ElderProfileService elderProfileService;
    private final AlertEventService alertEventService;

    /** 规则版本会写进命中明细，规则升级后仍能区分"哪一版规则判的" */
    @Value("${care.health.rule-version:v1}")
    private String ruleVersion;

    /** 注入的时间源，测试可替换，避免依赖真实机器时间 */
    private final Clock clock;

    /**
     * 录入一条健康记录。
     *
     * <p>整个过程在一个事务里：记录与命中明细要么都成功，要么都不留。
     * 只存记录不存证据，事后无法解释"为什么判为异常"。
     */
    @Transactional(rollbackFor = Exception.class)
    public HealthRecord create(CreateHealthRecordRequest request) {
        // 1. 输入校验
        request.requireAtLeastOneMetric();
        request.requireBloodPressurePair();
        request.requireValidRanges();
        request.requireValidPrecision();
        String scene = request.resolveScene();
        RecordMode mode = RecordMode.from(request.mode());

        // 2. 解析归属：管理员可代录，老人只能用自己那份
        Long elderId = elderProfileService.resolveTargetElderId(request.elderId());

        // 3. 防重：同一次提交重试直接返回原记录
        HealthRecord existing = findExistingForRetry(request);
        if (existing != null) {
            return existing;
        }

        // 4. 落库
        LocalDateTime now = LocalDateTime.now(clock);
        HealthRecord record = toEntity(request, elderId, mode, scene, now);
        healthRecordMapper.insert(record);

        // 5. 规则判定并保存证据快照
        List<HealthRule> hits = evaluateRules(record);
        hits.forEach(rule -> saveAnomaly(record, rule, now));

        // 6. 当前测量命中规则时生成或归并告警事件。
        //    历史补录不触发：三天前测的血压变成今天的紧急待办，是误报。
        if (mode == RecordMode.CURRENT) {
            for (HealthRule rule : hits) {
                AlertEvent event = alertEventService.recordHealthHit(
                        record.getElderId(),
                        rule.code(),
                        "健康记录 #" + record.getId() + " 命中 " + rule.description());
                // 把事件回写到证据，明细与事件才关联得上
                linkAnomalyToEvent(record.getId(), rule, event);
            }
        }

        return record;
    }

    /**
     * 按 requestId 查已存在的记录。
     *
     * <p>同键不同内容要报 409 而不是当成新记录——那说明客户端把
     * 一次提交的参数改了还沿用同一个防重键，属于异常用法。
     */
    private HealthRecord findExistingForRetry(CreateHealthRecordRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            return null;
        }
        HealthRecord existing = healthRecordMapper.findByCreatorAndRequest(
                LoginContext.requireUserId(), request.requestId());
        if (existing == null) {
            return null;
        }
        String existingHash = existing.getRequestHash();
        String currentHash = buildRequestHash(request);
        if (existingHash != null && !existingHash.equals(currentHash)) {
            throw BusinessException.conflict("相同请求标识提交了不同内容");
        }
        // 相同内容重试：直接返回原记录，不重复写入、不重复扣减任何业务计数
        return existing;
    }

    private HealthRecord toEntity(CreateHealthRecordRequest request,
                                  Long elderId,
                                  RecordMode mode,
                                  String scene,
                                  LocalDateTime now) {
        HealthRecord record = new HealthRecord();
        record.setElderId(elderId);
        record.setHeartRate(request.heartRate());
        // 归一化精度，保证防重摘要稳定
        record.setSystolic(CreateHealthRecordRequest.normalize(request.systolic(), 1));
        record.setDiastolic(CreateHealthRecordRequest.normalize(request.diastolic(), 1));
        record.setBloodSugar(CreateHealthRecordRequest.normalize(request.bloodSugar(), 2));
        record.setBodyTemperature(CreateHealthRecordRequest.normalize(request.bodyTemperature(), 1));
        record.setSleepHours(CreateHealthRecordRequest.normalize(request.sleepHours(), 1));
        record.setMeasureScene(scene);
        record.setMode(mode.name());
        record.setSource("MANUAL");
        record.setVoided(false);
        record.setMeasuredAt(mode.resolveMeasuredAt(request.measuredAt(), now));
        record.setCreatedBy(LoginContext.requireUserId());
        record.setRequestId(request.requestId());
        record.setRequestHash(buildRequestHash(request));
        record.setCreatedAt(now);
        return record;
    }

    /**
     * 对一条记录执行全部规则判定。
     *
     * <p>逐条规则独立判定，命中就各自留一份证据。
     * 不做"多指标累加成等级"——那种做法会丢掉"具体哪几项异常"的信息。
     */
    private List<HealthRule> evaluateRules(HealthRecord record) {
        List<HealthRule> hits = new ArrayList<>();
        for (HealthRule rule : HealthRule.values()) {
            BigDecimal value = record.valueOf(rule.metric().code());
            if (rule.matches(value)) {
                hits.add(rule);
            }
        }
        return hits;
    }

    /**
     * 把规则命中明细关联到告警事件。
     *
     * <p>明细与事件必须能互查，否则"为什么这条记录产生了这条告警"就断了。
     */
    private void linkAnomalyToEvent(Long recordId, HealthRule rule, AlertEvent event) {
        jdbcTemplate.update(
                "UPDATE health_record_anomaly SET event_id = ? "
                        + "WHERE health_record_id = ? AND rule_code = ?",
                event.getId(), recordId, rule.code());
    }

    private void saveAnomaly(HealthRecord record, HealthRule rule, LocalDateTime now) {
        HealthRecordAnomaly anomaly = new HealthRecordAnomaly();
        anomaly.setHealthRecordId(record.getId());
        anomaly.setRuleCode(rule.code());
        anomaly.setRuleVersion(ruleVersion);
        anomaly.setMetric(rule.metric().code());
        anomaly.setMeasuredValue(record.valueOf(rule.metric().code()));
        anomaly.setLowerBound(rule.lowerBound());
        anomaly.setUpperBound(rule.upperBound());
        anomaly.setUnit(rule.unit());
        anomaly.setHitAt(now);
        anomalyMapper.insert(anomaly);
    }

    /**
     * 作废一条记录。
     *
     * <p>不物理删除：作废只让它不再参与概览统计，
     * 已经由此触发的处置事件与处理记录必须保留。
     */
    @Transactional(rollbackFor = Exception.class)
    public void voidRecord(Long recordId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw BusinessException.badRequest("作废必须填写原因");
        }
        // 先校验访问权：老人不能作废别人的记录
        HealthRecord record = healthRecordMapper.selectById(recordId);
        if (record == null) {
            throw BusinessException.notFound("健康记录不存在");
        }
        // 调用本身即校验：越权时抛 404，不需要使用返回值
        elderProfileService.requireAccessible(record.getElderId());

        if (Boolean.TRUE.equals(record.getVoided())) {
            throw BusinessException.badRequest("该记录已作废");
        }

        HealthRecord update = new HealthRecord();
        update.setId(recordId);
        update.setVoided(true);
        update.setVoidReason(reason);
        update.setVoidedBy(LoginContext.requireUserId());
        update.setVoidedAt(LocalDateTime.now(clock));
        healthRecordMapper.updateById(update);
    }

    /**
     * 查询某位老人最近七天的有效记录。
     *
     * <p>口径是<b>今天及前 6 个自然日</b>（按服务器本地时区），不是"最近 7 条"——
     * 一天录多次时后者只剩两天数据，趋势图会骗人。作废记录不返回。
     */
    public List<HealthRecord> findLastSevenDays(Long elderId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDateTime start = today.minusDays(6).atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        return healthRecordMapper.findInRange(elderId, start, end);
    }

    /** 取最近一条有效记录（按实际测量时间） */
    public HealthRecord findLatest(Long elderId) {
        return healthRecordMapper.findLatest(elderId);
    }

    /**
     * 计算请求内容的标准摘要。
     *
     * <p>只包含业务输入，不含服务器时间或随机值——那会让同一次提交
     * 每次重试算出不同摘要，防重就失效了。
     */
    private String buildRequestHash(CreateHealthRecordRequest request) {
        String raw = String.join("|",
                nullSafe(request.systolic()),
                nullSafe(request.diastolic()),
                nullSafe(request.bloodSugar()),
                nullSafe(request.bodyTemperature()),
                nullSafe(request.sleepHours()),
                String.valueOf(request.heartRate()),
                nullSafe(request.measureScene()),
                nullSafe(request.mode()),
                nullSafe(request.measuredAt()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw BusinessException.badRequest("无法计算请求摘要");
        }
    }

    private String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }
}
