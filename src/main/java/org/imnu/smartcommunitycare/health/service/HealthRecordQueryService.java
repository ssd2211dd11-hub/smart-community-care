package org.imnu.smartcommunitycare.health.service;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.imnu.smartcommunitycare.common.api.PageQuery.PageResult;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.elder.service.ElderProfileService;
import org.imnu.smartcommunitycare.health.mapper.HealthRecordMapper;
import org.imnu.smartcommunitycare.health.model.entity.HealthRecord;
import org.imnu.smartcommunitycare.health.model.request.HealthRecordQuery;
import org.imnu.smartcommunitycare.health.model.response.HealthRecordResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.Set;
@Service @RequiredArgsConstructor
public class HealthRecordQueryService {
    private final ElderProfileService elders;
    private final HealthRecordMapper records;
    private final HealthRecordAssembler assembler;
    @Transactional(readOnly=true)
    public PageResult<HealthRecordResponse> page(HealthRecordQuery query) {
        Long elder=elders.resolveTargetElderId(query.getElderId());
        if(query.getFromDate()!=null&&query.getToDate()!=null&&query.getFromDate().isAfter(query.getToDate()))
            throw BusinessException.badRequest("开始日期不能晚于结束日期");
        if(LocalDate.MAX.equals(query.getToDate())) throw BusinessException.badRequest("结束日期超出范围");
        var filter=Wrappers.<HealthRecord>lambdaQuery().eq(HealthRecord::getElderId,elder);
        if(query.getFromDate()!=null)filter.ge(HealthRecord::getMeasuredAt,query.getFromDate().atStartOfDay());
        if(query.getToDate()!=null)filter.lt(HealthRecord::getMeasuredAt,query.getToDate().plusDays(1).atStartOfDay());
        if(query.getSource()!=null&&!query.getSource().isBlank()) {
            if(!Set.of("MANUAL","DEMO").contains(query.getSource()))throw BusinessException.badRequest("健康来源不合法");
            filter.eq(HealthRecord::getSource,query.getSource());
        }
        if(query.getMode()!=null&&!query.getMode().isBlank()) {
            if(!Set.of("CURRENT","HISTORY").contains(query.getMode()))throw BusinessException.badRequest("记录方式不合法");
            filter.eq(HealthRecord::getMode,query.getMode());
        }
        if(query.getVoided()!=null)filter.eq(HealthRecord::getVoided,query.getVoided());
        long total=records.selectCount(filter);
        var page=records.selectList(filter.orderByDesc(HealthRecord::getMeasuredAt).orderByDesc(HealthRecord::getId)
                .last("LIMIT "+query.safePageSize()+" OFFSET "+query.offset()));
        return PageResult.of(assembler.toResponses(page),total,query.safePageNum(),query.safePageSize());
    }
}
