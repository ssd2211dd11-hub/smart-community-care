package org.imnu.smartcommunitycare.workbench.controller;
import lombok.RequiredArgsConstructor;
import org.imnu.smartcommunitycare.common.api.ApiResponse;
import org.imnu.smartcommunitycare.workbench.model.WorkbenchResponse;
import org.imnu.smartcommunitycare.workbench.service.WorkbenchService;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class WorkbenchController {
    private final WorkbenchService service;
    @GetMapping("/api/workbench")
    public ApiResponse<WorkbenchResponse> summary(){return ApiResponse.success("查询成功",service.summary());}
}
