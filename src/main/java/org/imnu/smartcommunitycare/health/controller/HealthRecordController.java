package org.imnu.smartcommunitycare.health.controller;

import org.imnu.smartcommunitycare.common.api.ApiResponse;
import org.imnu.smartcommunitycare.elder.service.ElderProfileService;
import org.imnu.smartcommunitycare.health.model.request.CreateHealthRecordRequest;
import org.imnu.smartcommunitycare.health.model.response.HealthRecordResponse;
import org.imnu.smartcommunitycare.health.model.response.HealthSummaryResponse;
import org.imnu.smartcommunitycare.health.service.HealthRecordAssembler;
import org.imnu.smartcommunitycare.health.service.HealthRecordService;
import org.imnu.smartcommunitycare.health.service.HealthSummaryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 健康记录接口。
 *
 * <p>路径里<b>没有 userId</b>：老人自己的记录靠会话确定归属，
 * 管理员代录才显式传 elderId。URL 里能改的标识越少，越权面越小。
 */
@RestController
@RequestMapping("/api/health-records")
@RequiredArgsConstructor
public class HealthRecordController {

    private final HealthRecordService healthRecordService;
    private final HealthSummaryService healthSummaryService;
    private final HealthRecordAssembler assembler;
    private final ElderProfileService elderProfileService;
    private final org.imnu.smartcommunitycare.health.service.HealthRecordQueryService queryService;

    @GetMapping("/paged")
    public ApiResponse<?> page(org.imnu.smartcommunitycare.health.model.request.HealthRecordQuery query) {
        return ApiResponse.success("查询成功",queryService.page(query));
    }

    /**
     * 录入健康记录。
     *
     * <p>老人录入用body 里的 mode 区分当前测量与历史补录；
     * 管理员代录时必须传 elderId。
     */
    @PostMapping
    public ApiResponse<HealthRecordResponse> create(@Valid @RequestBody CreateHealthRecordRequest request) {
        return ApiResponse.success("录入成功", assembler.toResponse(healthRecordService.create(request)));
    }

    /**
     * 查询某位老人最近七天的记录。
     *
     * <p>老人不传 elderId 时默认查自己；管理员必须显式指定。
     */
    @GetMapping
    public ApiResponse<List<HealthRecordResponse>> list(@RequestParam(required = false) Long elderId) {
        Long targetId = elderProfileService.resolveTargetElderId(elderId);
        return ApiResponse.success("查询成功",
                assembler.toResponses(healthRecordService.findLastSevenDays(targetId)));
    }

    /**
     * 七日健康概览。
     *
     * <p>按今天及前 6 个自然日统计，返回各指标的样本数、均值、极值与命中次数，
     * 以及按天分组的趋势数据。口径见 {@code HealthSummaryResponse} 的类注释。
     */
    @GetMapping("/summary")
    public ApiResponse<HealthSummaryResponse> summary(@RequestParam(required = false) Long elderId) {
        Long targetId = elderProfileService.resolveTargetElderId(elderId);
        return ApiResponse.success("查询成功", healthSummaryService.summarize(targetId));
    }

    /** 最近一条有效记录 */
    @GetMapping("/latest")
    public ApiResponse<HealthRecordResponse> latest(@RequestParam(required = false) Long elderId) {
        Long targetId = elderProfileService.resolveTargetElderId(elderId);
        return ApiResponse.success("查询成功", assembler.toResponse(healthRecordService.findLatest(targetId)));
    }

    /**
     * 作废一条记录。
     *
     * <p>不物理删除：已经由此触发的处置过程必须保留。
     */
    @PostMapping("/{recordId}/void")
    public ApiResponse<Void> voidRecord(@PathVariable Long recordId,
                                       @RequestParam String reason) {
        healthRecordService.voidRecord(recordId, reason);
        return ApiResponse.success("已作废");
    }
}
