package org.imnu.smartcommunitycare.catalog.controller;

import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.catalog.service.CatalogService;
import org.imnu.smartcommunitycare.common.api.ApiResponse;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务目录与时段接口。
 *
 * <p>读接口对所有已登录角色开放（老人要浏览），
 * 写接口的角色校验在 Service 层统一做，不依赖前端隐藏按钮。
 */
@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;
    @GetMapping("/paged")
    public ApiResponse<?> page(org.imnu.smartcommunitycare.catalog.model.request.CatalogPageQuery query,
                               @RequestParam(defaultValue="false") boolean all) {
        return ApiResponse.success("查询成功",catalogService.pageItems(query,all));
    }
    @GetMapping("/{serviceId}/slots/paged")
    public ApiResponse<?> slotsPage(@PathVariable Long serviceId,org.imnu.smartcommunitycare.common.api.PageQuery query,
                                    @RequestParam(defaultValue="false") boolean all) {
        return ApiResponse.success("查询成功",catalogService.pageSlots(serviceId,query,all));
    }

    /**
     * 服务目录。
     *
     * <p>默认只返回<b>上架中</b>的服务，供老人浏览与预约。
     *
     * <p>管理员需要看到<b>全部</b>（含已下架）才能维护——
     * 否则下架后服务从列表消失，管理员就再也点不到「上架」把它恢复。
     * 用 {@code all=true} 切换，与下方 {@code /slots} 的 {@code all} 同一套约定，
     * 不新增同义接口。
     */
    @GetMapping
    public ApiResponse<List<ServiceItem>> list(
            @RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.success("查询成功",
                all ? catalogService.listAll() : catalogService.listEnabled());
    }

    /**
     * 某服务的时段。
     *
     * <p>默认只返回<b>未来且开放</b>的时段，供老人预约页使用。
     * 管理员需要看到全部（含已关闭、已过期）才能维护，
     * 用 {@code all=true} 切换；不新增接口，避免同义接口并存。
     */
    @GetMapping("/{serviceId}/slots")
    public ApiResponse<List<ServiceSlot>> slots(@PathVariable Long serviceId,
                                               @RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.success("查询成功",
                all ? catalogService.listSlots(serviceId)
                    : catalogService.listFutureOpenSlots(serviceId));
    }

    /** 新建服务（管理员） */
    @PostMapping
    public ApiResponse<ServiceItem> create(@RequestBody ServiceItem item) {
        return ApiResponse.success("创建成功", catalogService.createItem(item));
    }

    /** 更新服务（管理员） */
    @PutMapping("/{serviceId}")
    public ApiResponse<ServiceItem> update(@PathVariable Long serviceId,
                                          @RequestBody ServiceItem item) {
        return ApiResponse.success("更新成功", catalogService.updateItem(serviceId, item));
    }

    /** 下架服务（管理员）。不取消已有订单 */
    @PutMapping("/{serviceId}/disable")
    public ApiResponse<Void> disable(@PathVariable Long serviceId) {
        catalogService.disableItem(serviceId);
        return ApiResponse.success("已下架");
    }

    /**
     * 上架服务（管理员）。与下架对称，不取消已有订单。
     *
     * <p>用 {@code /enable} 独立成端点，而不是给 {@code /disable} 加一个
     * 「是否反向」的布尔参数：上架与下架是两个独立业务动作，
     * 合成一个端点会让调用方难以表达意图，也不好单独加权限与审计。
     */
    @PutMapping("/{serviceId}/enable")
    public ApiResponse<Void> enable(@PathVariable Long serviceId) {
        catalogService.enableItem(serviceId);
        return ApiResponse.success("已上架");
    }

    /** 创建时段（管理员） */
    @PostMapping("/{serviceId}/slots")
    public ApiResponse<ServiceSlot> createSlot(@PathVariable Long serviceId,
                                               @RequestParam String startAt,
                                               @RequestParam String endAt,
                                               @RequestParam int capacity) {
        ServiceSlot slot = catalogService.createSlot(
                serviceId, parseDateTime(startAt), parseDateTime(endAt), capacity);
        return ApiResponse.success("创建成功", slot);
    }

    /** 调整容量（管理员）。不得低于已占用数 */
    @PutMapping("/slots/{slotId}/capacity")
    public ApiResponse<ServiceSlot> updateCapacity(@PathVariable Long slotId,
                                                   @RequestParam int capacity) {
        return ApiResponse.success("调整成功", catalogService.updateCapacity(slotId, capacity));
    }

    /** 关闭时段受理（管理员）。不动开始结束时间 */
    @PutMapping("/slots/{slotId}/close")
    public ApiResponse<Void> closeSlot(@PathVariable Long slotId) {
        catalogService.closeSlot(slotId);
        return ApiResponse.success("已关闭受理");
    }

    /**
     * 重新开放时段受理（管理员）。与关闭对称。
     *
     * <p>同样<b>不动开始结束时间、容量与已占用</b>，也不动已有订单——
     * 误关的时段要能恢复，而不是让已预约的老人改期。
     */
    @PutMapping("/slots/{slotId}/reopen")
    public ApiResponse<Void> reopenSlot(@PathVariable Long slotId) {
        catalogService.reopenSlot(slotId);
        return ApiResponse.success("已重新开放");
    }

    /** 解析 ISO 日期时间；格式错就明确报错，不返回 null 让后面NPE */
    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            throw BusinessException.badRequest("时间格式不正确，应为 yyyy-MM-ddTHH:mm:ss");
        }
    }
}
