package org.imnu.smartcommunitycare.device.controller;

import org.imnu.smartcommunitycare.common.api.ApiResponse;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.device.model.request.CreateDeviceRequest;
import org.imnu.smartcommunitycare.device.model.request.SimulationModeRequest;
import org.imnu.smartcommunitycare.device.model.request.SimulatorCommandRequest;
import org.imnu.smartcommunitycare.device.model.request.UpdateDeviceRequest;
import org.imnu.smartcommunitycare.device.model.response.AssignableElderResponse;
import org.imnu.smartcommunitycare.device.model.response.CommandView;
import org.imnu.smartcommunitycare.device.model.response.DeviceView;
import org.imnu.smartcommunitycare.device.service.DeviceService;
import org.imnu.smartcommunitycare.device.config.SimulatorProperties;
import org.imnu.smartcommunitycare.device.model.response.SimulatorStatusResponse;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 设备接口。
 *
 * <p><b>设备数据全部为系统内置模拟，不接真实硬件。</b>
 * 接口返回成功表示「指令已保存」，不表示设备已动作——
 * 执行由内置调度器在后续轮次完成，页面需轮询指令状态。
 *
 * <p>动作路径一律用业务动词，不提供 {@code PUT /{id}/status} 这类通用入口：
 * 启动模拟器、切场景、下发开关是三件不同的事，权限与副作用都不一样。
 */
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceController {

    @GetMapping("/paged")
    public ApiResponse<?> page(org.imnu.smartcommunitycare.device.model.request.DevicePageQuery query) {
        return ApiResponse.success("查询成功",deviceService.page(query));
    }
    @GetMapping("/{deviceId}/commands/paged")
    public ApiResponse<?> commandsPage(@PathVariable Long deviceId,org.imnu.smartcommunitycare.common.api.PageQuery query) {
        return ApiResponse.success("查询成功",deviceService.commandsPage(deviceId,query));
    }

    private final DeviceService deviceService;
    private final ElderProfileMapper elderProfileMapper;
    private final SimulatorProperties simulatorProperties;
    private final Clock clock;

    @GetMapping("/simulator-status")
    public ApiResponse<SimulatorStatusResponse> simulatorStatus() {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        return ApiResponse.success("查询成功", new SimulatorStatusResponse(
                simulatorProperties.isEnabled(),
                LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).toString(),
                "Asia/Shanghai", List.of("LIGHT", "AIRCON", "CURTAIN")));
    }

    /**
     * 设备列表。
     *
     * <p>管理员看全部（含停用），老人只看自己的。用同一入口按角色收窄，
     * 不另开 {@code /mine}——同一个「我的设备」概念出现两个入口，
     * 迟早会有人用错。
     */
    @GetMapping
    public ApiResponse<List<DeviceView>> list() {
        // 三个角色分别显式处理，不用「是否管理员」的二元判断兜底：
        // 二元写法会把服务人员顺带放进老人分支，等于给了越权读取。
        UserRole role = LoginContext.requireRole();
        List<DeviceView> result = switch (role) {
            case COMMUNITY_ADMIN -> deviceService.listForAdmin();
            case ELDER -> deviceService.listForElder(currentElderProfileId());
            case SERVICE_STAFF -> throw BusinessException.forbidden("服务人员不能查看设备档案");
        };
        return ApiResponse.success("查询成功", result);
    }

    /**
     * 可选归属的老人（管理员建档时用）。
     *
     * <p>返回的是 elder_profile.id，与 device.elder_id 同一坐标系。
     */
    @GetMapping("/assignable-elders")
    public ApiResponse<List<AssignableElderResponse>> assignableElders() {
        return ApiResponse.success("查询成功", deviceService.listAssignableElders());
    }

    /** 新建设备（管理员） */
    @PostMapping
    public ApiResponse<DeviceView> create(@Valid @RequestBody CreateDeviceRequest request) {
        return ApiResponse.success("创建成功", deviceService.create(request));
    }

    /** 修改设备档案（管理员）。设备编号不可改 */
    @PutMapping("/{deviceId}")
    public ApiResponse<DeviceView> update(
            @PathVariable Long deviceId,
            @Valid @RequestBody UpdateDeviceRequest request) {
        return ApiResponse.success("修改成功", deviceService.update(deviceId, request));
    }

    /**
     * 启动或停止虚拟设备（管理员）。
     *
     * <p>用显式的两个端点而不是 {@code PUT /simulator} 加 body 开关：
     * 「启动」和「停止」在业务上不是同一件事的两种取值，
     * 前者的前置条件是设备启用，后者还要作废待执行指令。
     */
    @PostMapping("/{deviceId}/simulator/start")
    public ApiResponse<DeviceView> startSimulator(@PathVariable Long deviceId) {
        return ApiResponse.success("模拟器已启动", deviceService.setSimulatorRunning(deviceId, true));
    }

    @PostMapping("/{deviceId}/simulator/stop")
    public ApiResponse<DeviceView> stopSimulator(@PathVariable Long deviceId) {
        return ApiResponse.success("模拟器已停止", deviceService.setSimulatorRunning(deviceId, false));
    }

    /**
     * 切换执行场景（管理员）。
     *
     * <p>场景是「下一条指令怎么执行」的开关，不是设备状态，
     * 所以走 {@code /simulation-mode} 而不是改设备档案。
     */
    @PostMapping("/{deviceId}/simulation-mode")
    public ApiResponse<DeviceView> setSimulationMode(
            @PathVariable Long deviceId,
            @Valid @RequestBody SimulationModeRequest request) {
        return ApiResponse.success("场景已切换", deviceService.setSimulationMode(deviceId, request));
    }

    /**
     * 下发开关指令。
     *
     * <p>成功响应的文案是「指令已提交」，不是「已开启」——
     * 此时设备还没动。真实结果要等指令状态变为终态。
     */
    @PostMapping("/{deviceId}/commands")
    public ApiResponse<CommandView> submitCommand(
            @PathVariable Long deviceId,
            @Valid @RequestBody SimulatorCommandRequest request) {
        return ApiResponse.success("指令已提交，等待设备执行",
                deviceService.submitCommand(deviceId, request));
    }

    /** 某设备的指令历史 */
    @GetMapping("/{deviceId}/commands")
    public ApiResponse<List<CommandView>> listCommands(@PathVariable Long deviceId) {
        return ApiResponse.success("查询成功", deviceService.listCommands(deviceId));
    }

    /** 全部设备的指令历史（管理员），用于排查调度异常 */
    @GetMapping("/commands")
    public ApiResponse<List<CommandView>> listAllCommands() {
        return ApiResponse.success("查询成功", deviceService.listAllCommands());
    }

    /**
     * 取当前登录者的老人档案 ID。
     *
     * <p>一个账号可能没有老人档案（例如服务人员误入此页面），
     * 此时按空处理返回空列表，不抛异常——页面应显示「暂无设备」，
     * 而不是弹一个看不懂的报错。
     */
    private Long currentElderProfileId() {
        ElderProfile profile = elderProfileMapper.findByUserId(LoginContext.requireUserId());
        return profile == null ? null : profile.getId();
    }
}
