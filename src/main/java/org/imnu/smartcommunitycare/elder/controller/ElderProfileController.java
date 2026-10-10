package org.imnu.smartcommunitycare.elder.controller;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.imnu.smartcommunitycare.common.api.ApiResponse;
import org.imnu.smartcommunitycare.elder.model.request.UpdateElderRequest;
import org.imnu.smartcommunitycare.elder.service.ElderManagementService;
import org.imnu.smartcommunitycare.user.model.request.AdminAccountQuery;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/elder-profiles")
public class ElderProfileController {
    private final ElderManagementService service;
    @GetMapping("/paged") public ApiResponse<?> list(AdminAccountQuery query) {return ApiResponse.success("查询成功",service.list(query));}
    @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable Long id) {return ApiResponse.success("查询成功",service.detail(id));}
    @PutMapping("/{id}") public ApiResponse<Void> update(@PathVariable Long id,@Valid @RequestBody UpdateElderRequest request) {
        service.update(id,request);return ApiResponse.success("档案已保存");
    }
}
