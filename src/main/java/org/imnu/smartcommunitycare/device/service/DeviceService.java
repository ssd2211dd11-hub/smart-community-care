package org.imnu.smartcommunitycare.device.service;

import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.device.config.SimulatorProperties;
import org.imnu.smartcommunitycare.device.mapper.DeviceCommandMapper;
import org.imnu.smartcommunitycare.device.mapper.DeviceMapper;
import org.imnu.smartcommunitycare.device.model.entity.Device;
import org.imnu.smartcommunitycare.device.model.entity.DeviceCommand;
import org.imnu.smartcommunitycare.device.model.enums.DeviceCommandStatus;
import org.imnu.smartcommunitycare.device.model.enums.OnlineStatus;
import org.imnu.smartcommunitycare.device.model.enums.PowerStatus;
import org.imnu.smartcommunitycare.device.model.enums.SimulationMode;
import org.imnu.smartcommunitycare.device.model.request.CreateDeviceRequest;
import org.imnu.smartcommunitycare.device.model.request.SimulationModeRequest;
import org.imnu.smartcommunitycare.device.model.request.SimulatorCommandRequest;
import org.imnu.smartcommunitycare.device.model.request.UpdateDeviceRequest;
import org.imnu.smartcommunitycare.device.model.response.AssignableElderResponse;
import org.imnu.smartcommunitycare.device.model.response.CommandView;
import org.imnu.smartcommunitycare.device.model.response.DeviceView;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.user.mapper.SysUserMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 设备档案与模拟控制指令。
 *
 * <p><b>本类涉及的一切设备数据都是模拟的</b>，不接任何真实硬件。
 * 控制指令走「提交 → PENDING → 调度器执行 → 终态」两段式，
 * 因此接口返回成功<b>只表示指令已保存</b>，不表示设备已经动作——
 * 页面必须表达出这个区别，否则管理员会以为灯已经开了。
 *
 * <p><b>统一锁顺序：设备行 → 指令行</b>，与订单模块的约定同源。
 */
@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceMapper deviceMapper;
    private final DeviceCommandMapper commandMapper;
    private final ElderProfileMapper elderProfileMapper;
    private final SysUserMapper userMapper;
    private final SimulatorProperties simulatorProperties;
    private final Clock clock;
    private final org.imnu.smartcommunitycare.elder.service.ElderProfileService elders;

    @Transactional(readOnly=true)
    public org.imnu.smartcommunitycare.common.api.PageQuery.PageResult<DeviceView> page(
            org.imnu.smartcommunitycare.device.model.request.DevicePageQuery query) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN,UserRole.ELDER);
        var filter=com.baomidou.mybatisplus.core.toolkit.Wrappers.<Device>lambdaQuery();
        if(LoginContext.requireRole()==UserRole.ELDER||query.getElderId()!=null)
            filter.eq(Device::getElderId,elders.resolveTargetElderId(query.getElderId()));
        if(query.getKeyword()!=null&&!query.getKeyword().isBlank()) {
            String key=query.getKeyword().trim();filter.and(w->w.like(Device::getDeviceName,key).or().like(Device::getDeviceNo,key));
        }
        if(query.getDeviceType()!=null&&!query.getDeviceType().isBlank()) {
            if(!java.util.Set.of("LIGHT","AIRCON","CURTAIN").contains(query.getDeviceType()))throw BusinessException.badRequest("设备类型不合法");
            filter.eq(Device::getDeviceType,query.getDeviceType());
        }
        if(query.getEnabled()!=null)filter.eq(Device::getEnabled,query.getEnabled());
        if(query.getOnlineStatus()!=null&&!query.getOnlineStatus().isBlank()) {
            LocalDateTime cutoff=LocalDateTime.now(clock).minusNanos(simulatorProperties.getOfflineThresholdMs()*1_000_000L);
            switch(query.getOnlineStatus()) {
                case "DISABLED" -> filter.eq(Device::getEnabled,false);
                case "ONLINE" -> filter.eq(Device::getEnabled,true).ge(Device::getLastSeenAt,cutoff);
                case "OFFLINE" -> filter.eq(Device::getEnabled,true).and(w->w.isNull(Device::getLastSeenAt).or().lt(Device::getLastSeenAt,cutoff));
                default -> throw BusinessException.badRequest("设备状态不合法");
            }
        }
        long total=deviceMapper.selectCount(filter);
        var rows=deviceMapper.selectList(filter.orderByDesc(Device::getCreatedAt).orderByDesc(Device::getId)
                .last("LIMIT "+query.safePageSize()+" OFFSET "+query.offset()));
        return org.imnu.smartcommunitycare.common.api.PageQuery.PageResult.of(toViews(rows),total,query.safePageNum(),query.safePageSize());
    }

    @Transactional(readOnly=true)
    public org.imnu.smartcommunitycare.common.api.PageQuery.PageResult<CommandView> commandsPage(Long id,
            org.imnu.smartcommunitycare.common.api.PageQuery query) {
        Device device=requireDevice(id);requireViewPermission(device);
        var filter=com.baomidou.mybatisplus.core.toolkit.Wrappers.<DeviceCommand>lambdaQuery().eq(DeviceCommand::getDeviceId,id);
        long total=commandMapper.selectCount(filter);LocalDateTime now=LocalDateTime.now(clock);
        var rows=commandMapper.selectList(filter.orderByDesc(DeviceCommand::getCreatedAt).orderByDesc(DeviceCommand::getId)
                .last("LIMIT "+query.safePageSize()+" OFFSET "+query.offset()));
        return org.imnu.smartcommunitycare.common.api.PageQuery.PageResult.of(rows.stream().map(c->toCommandView(c,device.getDeviceName(),now)).toList(),total,query.safePageNum(),query.safePageSize());
    }

    /** 指令动作，首版只有设置开关 */
    private static final String ACTION_SET_POWER = "SET_POWER";

    // ==================== 设备档案 ====================

    /**
     * 管理员看全部设备。
     *
     * <p>不过滤启用状态：管理员必须能看到已停用的设备，
     * 否则设备一旦停用就从管理视野里消失了，再也找不回来。
     */
    public List<DeviceView> listForAdmin() {
        return toViews(deviceMapper.selectList(null));
    }

    /**
     * 老人只看自己的设备。
     *
     * <p>用老人档案 ID 过滤，不是账号 ID——device.elder_id 存的是档案主键。
     */
    public List<DeviceView> listForElder(Long elderProfileId) {
        if (elderProfileId == null) {
            return List.of();
        }
        return toViews(deviceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Device>()
                        .eq(Device::getElderId, elderProfileId)
                        .orderByAsc(Device::getId)));
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceView create(CreateDeviceRequest request) {
        requireAdmin();

        if (deviceMapper.findByNo(request.deviceNo()) != null) {
            throw BusinessException.conflict("设备编号已存在：" + request.deviceNo());
        }
        if (request.elderId() != null) {
            requireElderExists(request.elderId());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Device device = new Device();
        device.setDeviceNo(request.deviceNo());
        device.setDeviceName(request.deviceName());
        device.setDeviceType(request.deviceType());
        device.setElderId(request.elderId());
        // 首版固定模拟接入，不接受前端指定
        device.setAccessMode("SIMULATED");
        device.setLocation(request.location());
        device.setEnabled(true);
        // 新建设备不自动起模拟器：管理员需显式启动，避免批量建档后设备集体上线
        device.setSimulatorRunning(false);
        device.setSimulationMode(SimulationMode.NORMAL.name());
        device.setSimulatedPowerStatus(PowerStatus.OFF.name());
        // 首个心跳前不上报状态，留空比填 OFF 诚实——「还没报告过」不等于「已关闭」
        device.setPowerStatus(null);
        device.setLastSeenAt(null);
        device.setVersion(1);
        device.setCreatedAt(now);
        device.setUpdatedAt(now);
        try {
            deviceMapper.insert(device);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("设备编号已存在");
        }

        return toView(device);
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceView update(Long deviceId, UpdateDeviceRequest request) {
        requireAdmin();

        Device device = requireDeviceForUpdate(deviceId);
        // 设备编号不可改，这里也不接受它
        if (request.deviceType() != null && !request.deviceType().equals(device.getDeviceType())) {
            throw BusinessException.badRequest("设备类型不支持变更");
        }
        boolean transferring = request.elderId() != null && !request.elderId().equals(device.getElderId());
        if (transferring) {
            requireElderExists(request.elderId());
        }

        if (request.deviceName() != null && !request.deviceName().isBlank()) {
            device.setDeviceName(request.deviceName());
        }
        if (request.elderId() != null) {
            device.setElderId(request.elderId());
        }
        if (request.location() != null) {
            device.setLocation(request.location());
        }

        boolean disabling = Boolean.FALSE.equals(request.enabled())
                && Boolean.TRUE.equals(device.getEnabled());
        if (request.enabled() != null) {
            device.setEnabled(request.enabled());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        device.setUpdatedAt(now);
        device.setVersion(device.getVersion() + 1);
        deviceMapper.updateById(device);

        // 停用会让待执行指令失效：不这样做，指令到期前设备已不可用，
        // 模拟器还会继续对它执行
        if (disabling || transferring) {
            commandMapper.timeoutExpired(deviceId, now);
            commandMapper.cancelPending(deviceId, disabling ? "设备已停用" : "设备归属已变更", now);
        }

        return toView(device);
    }

    /**
     * 可选归属的老人列表（管理员建档时用）。
     *
     * <p>只列启用账号下的档案：停用账号不该继续接收新设备，
     * 否则会出现「设备挂在一个已停用账号名下」的记录。
     */
    public List<AssignableElderResponse> listAssignableElders() {
        requireAdmin();
        List<ElderProfile> profiles = elderProfileMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ElderProfile>()
                        .orderByAsc(ElderProfile::getId));
        if (profiles.isEmpty()) {
            return List.of();
        }

        Map<Long, SysUser> users = new HashMap<>();
        userMapper.selectBatchIds(profiles.stream().map(ElderProfile::getUserId).toList())
                .forEach(u -> users.put(u.getId(), u));

        List<AssignableElderResponse> result = new java.util.ArrayList<>();
        for (ElderProfile p : profiles) {
            SysUser u = users.get(p.getUserId());
            // 停用账号不列：设备挂在已停用账号名下，后续没人能管它
            if (u != null && Boolean.TRUE.equals(u.getEnabled())) {
                result.add(new AssignableElderResponse(p.getId(), u.getRealName()));
            }
        }
        return result;
    }

    // ==================== 模拟器控制 ====================

    /**
     * 启动/停止虚拟设备。
     *
     * <p>停止时不清除已上报状态：设备只是不再心跳了，
     * 它的历史状态是真实发生过的。离线判定交给心跳阈值。
     */
    @Transactional(rollbackFor = Exception.class)
    public DeviceView setSimulatorRunning(Long deviceId, boolean running) {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN, UserRole.ELDER);
        Device device = requireDeviceForUpdate(deviceId);
        requireControlPermission(device);
        requireSimulatorEnabled();
        if (running && !Boolean.TRUE.equals(device.getEnabled())) {
            throw BusinessException.conflict("设备已停用，不能启动模拟器");
        }
        if (Boolean.TRUE.equals(device.getSimulatorRunning()) == running) {
            return toView(device);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        deviceMapper.updateSimulatorRunning(deviceId, running, now);

        if (running) {
            // 刚启动还没有心跳，立刻给一个初始上报，
            // 否则页面会显示「OFFLINE」让人以为启动失败
            String initial = device.getSimulatedPowerStatus() == null
                    ? PowerStatus.OFF.name() : device.getSimulatedPowerStatus();
            deviceMapper.updateSimulatedPowerStatus(deviceId, initial, now);
            deviceMapper.writeHeartbeat(deviceId, initial, now);
        } else {
            // 停机前把仍有效的待执行指令收口，否则它们会永远停在 PENDING
            commandMapper.timeoutExpired(deviceId, now);
            commandMapper.cancelPending(deviceId, "模拟器已停止", now);
        }

        // 以本事务真实写入结果组装：停止不会伪造心跳，SQL 推进的版本不能丢失。
        return toView(deviceMapper.selectById(deviceId));
    }

    @Transactional(rollbackFor = Exception.class)
    public DeviceView setSimulationMode(Long deviceId, SimulationModeRequest request) {
        requireAdmin();
        requireSimulatorEnabled();

        Device device = requireDeviceForUpdate(deviceId);
        SimulationMode mode = SimulationMode.valueOf(request.mode());
        deviceMapper.updateSimulationMode(deviceId, mode.name(), LocalDateTime.now(clock));
        return toView(deviceMapper.selectById(deviceId));
    }

    // ==================== 控制指令 ====================

    /**
     * 提交开关指令。
     *
     * <p>返回 PENDING 指令，<b>不是执行结果</b>。模拟器在后续轮次执行它。
     *
     * <p>幂等：同一提交人 + 同一 requestId 重复提交返回同一条指令，
     * 不重复下发；同键但内容不同按冲突拒绝——那说明客户端把
     * requestId 用在了两次不同的操作上，静默返回旧指令会掩盖问题。
     */
    @Transactional(rollbackFor = Exception.class)
    public CommandView submitCommand(Long deviceId, SimulatorCommandRequest request) {
        Device device = requireDeviceForUpdate(deviceId);
        Long requester = LoginContext.requireUserId();
        requireControlPermission(device);

        String requestId = (request.requestId() == null || request.requestId().isBlank())
                ? UUID.randomUUID().toString()
                : request.requestId().trim();
        String target = request.targetPowerStatus();
        String hash = hash(deviceId + "|" + ACTION_SET_POWER + "|" + target);

        DeviceCommand existing = commandMapper.findByRequest(requester, requestId);
        if (existing != null) {
            if (!existing.getRequestHash().equals(hash)) {
                throw BusinessException.conflict("请求标识已用于其他操作，请重新提交");
            }
            return toCommandView(existing, device.getDeviceName(), LocalDateTime.now(clock));
        }

        // 已接受请求的确认不再次下发；新指令才要求执行者与设备可用。
        requireSimulatorEnabled();
        requireControllable(device);

        // 同一设备已有待执行指令时不再叠加：
        // 用户连点两次会让两条指令先后执行，后一条悄悄覆盖前一条的结果
        DeviceCommand pending = commandMapper.findPending(deviceId);
        if (pending != null && pending.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw BusinessException.conflict("该设备还有指令未确认，请等待结果或等待其到期");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        DeviceCommand command = new DeviceCommand();
        command.setDeviceId(deviceId);
        // 归属快照：之后转移设备，历史指令仍指向当时的老人
        command.setElderIdSnapshot(device.getElderId());
        command.setRequestedBy(requester);
        command.setRequestId(requestId);
        command.setRequestHash(hash);
        command.setAction(ACTION_SET_POWER);
        command.setTargetPowerStatus(target);
        command.setStatus(DeviceCommandStatus.PENDING.name());
        command.setReadyAt(now.plusNanos(
                simulatorProperties.getCommandReadyDelayMs() * 1_000_000L));
        command.setExpiresAt(now.plusNanos(
                simulatorProperties.getCommandExpireMs() * 1_000_000L));
        command.setCreatedAt(now);
        command.setUpdatedAt(now);
        try {
            commandMapper.insert(command);
        } catch (DuplicateKeyException e) {
            // 不同设备各自持行锁时，同一提交人仍可能竞争全局防重键。
            // 同设备重试由设备锁串行；跨设备碰撞内容必不同，明确返回冲突。
            throw BusinessException.conflict("请求标识已用于其他操作，请使用新的请求标识");
        }

        return toCommandView(command, device.getDeviceName(), now);
    }

    /** 某设备的指令历史 */
    public List<CommandView> listCommands(Long deviceId) {
        Device device = requireDevice(deviceId);
        requireViewPermission(device);

        LocalDateTime now = LocalDateTime.now(clock);
        return commandMapper.findByDevice(deviceId).stream()
                .map(c -> toCommandView(c, device.getDeviceName(), now))
                .toList();
    }

    /**
     * 全量指令历史（管理员）。
     *
     * <p>调度器执行失败、指令大量堆积时用来排查，
     * 因此需要跨设备视角。
     */
    public List<CommandView> listAllCommands() {
        requireAdmin();
        LocalDateTime now = LocalDateTime.now(clock);
        List<Device> devices = deviceMapper.selectList(null);
        Map<Long, String> names = new HashMap<>();
        devices.forEach(d -> names.put(d.getId(), d.getDeviceName()));

        List<DeviceCommand> commands = commandMapper.selectList(null);
        return commands.stream()
                .map(c -> toCommandView(c, names.get(c.getDeviceId()), now))
                .toList();
    }

    // ==================== 视图组装 ====================

    private List<DeviceView> toViews(List<Device> devices) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, String> names = elderNames(devices);
        return devices.stream()
                .map(d -> toView(d, now, names.get(d.getElderId())))
                .toList();
    }

    private DeviceView toView(Device device, String elderName) {
        return toView(device, LocalDateTime.now(clock), elderName);
    }

    /**
     * 组装设备视图，把设备与模拟器状态合成页面直接可用的结论。
     *
     * <p>全局启用、设备启用、模拟器运行、有效心跳由 canControl 判断；
     * 操作者权限另在正常接口入口核验，不依赖页面开关禁用。
     */
    private DeviceView toView(Device device, LocalDateTime now, String elderName) {
        boolean controlable = canControl(device);
        return DeviceView.from(device,
                onlineStatus(device, now).name(),
                controlable,
                controlBlockedReason(device),
                elderName);
    }

    private DeviceView toView(Device device) {
        return toView(device, LocalDateTime.now(clock), elderNameOf(device.getElderId()));
    }

    private OnlineStatus onlineStatus(Device device, LocalDateTime now) {
        if (!Boolean.TRUE.equals(device.getEnabled())) {
            return OnlineStatus.DISABLED;
        }
        if (simulatorProperties.isOffline(device.getLastSeenAt(), now)) {
            return OnlineStatus.OFFLINE;
        }
        return OnlineStatus.ONLINE;
    }

    /**
     * 设备自身是否处于可控制状态。
     *
     * <p>与操作者权限分开判断：设备停用、模拟器没启动时，
     * 管理员和老人都不该能下发指令——那是设备状态问题，不是权限问题。
     */
    private void requireControllable(Device device) {
        String reason = controlBlockedReason(device);
        if (reason != null) {
            throw BusinessException.conflict(reason + "，暂不能下发控制指令");
        }
    }

    /** 能否下发指令的前半段：设备自身状态。与操作者权限无关 */
    private boolean canControl(Device device) {
        return controlBlockedReason(device) == null;
    }

    private String controlBlockedReason(Device device) {
        if (!simulatorProperties.isEnabled()) {
            return "设备模拟器未启用";
        }
        if (!Boolean.TRUE.equals(device.getEnabled())) {
            return "设备已停用";
        }
        if (!Boolean.TRUE.equals(device.getSimulatorRunning())) {
            return "模拟器未启动";
        }
        if (simulatorProperties.isOffline(device.getLastSeenAt(), LocalDateTime.now(clock))) {
            return "设备离线";
        }
        return null;
    }

    private CommandView toCommandView(DeviceCommand c, String deviceName, LocalDateTime now) {
        DeviceCommandStatus effective = DeviceCommandStatus.from(c.getStatus())
                .effectiveAt(now, c.getExpiresAt());
        return CommandView.from(c, effective.name(), deviceName);
    }

    /**
     * 批量取老人姓名，键为 elder_profile.id。
     *
     * <p>姓名在 {@code sys_user.real_name}，不在老人档案表里，
     * 所以要档案 → 账号走一跳。列表页逐个查会变成 N+1。
     */
    private Map<Long, String> elderNames(List<Device> devices) {
        List<Long> ids = devices.stream()
                .map(Device::getElderId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> profileToUser = new HashMap<>();
        for (ElderProfile p : elderProfileMapper.selectBatchIds(ids)) {
            profileToUser.put(p.getId(), p.getUserId());
        }
        if (profileToUser.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> userToName = new HashMap<>();
        for (SysUser u : userMapper.selectBatchIds(profileToUser.values())) {
            userToName.put(u.getId(), u.getRealName());
        }
        Map<Long, String> result = new HashMap<>();
        profileToUser.forEach((profileId, userId) ->
                result.put(profileId, userToName.get(userId)));
        return result;
    }

    private String elderNameOf(Long elderProfileId) {
        if (elderProfileId == null) {
            return null;
        }
        return lookupElderName(elderProfileId);
    }

    /** 单个老人姓名，供详情/命令视图用（那里只有一台设备，不存在 N+1） */
    private String lookupElderName(Long elderProfileId) {
        ElderProfile profile = elderProfileMapper.selectById(elderProfileId);
        if (profile == null) {
            return null;
        }
        SysUser user = userMapper.selectById(profile.getUserId());
        return user == null ? null : user.getRealName();
    }

    // ==================== 校验与取数 ====================

    private Device requireDevice(Long deviceId) {
        Device device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw BusinessException.notFound("设备不存在");
        }
        return device;
    }

    private Device requireDeviceForUpdate(Long deviceId) {
        Device device = deviceMapper.findByIdForUpdate(deviceId);
        if (device == null) {
            throw BusinessException.notFound("设备不存在");
        }
        return device;
    }

    private void requireElderExists(Long elderProfileId) {
        ElderProfile profile = elderProfileMapper.selectById(elderProfileId);
        if (profile == null) {
            throw BusinessException.badRequest("指定的老人档案不存在");
        }
        SysUser user = userMapper.selectById(profile.getUserId());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw BusinessException.badRequest("指定的老人账号已停用");
        }
    }

    /**
     * 控制权限。
     *
     * <p>管理员可控制任何设备；老人只能控制自己名下的设备。
     * 先判角色再判归属——老人访问他人设备返回 404 而不是 403，
     * 避免通过错误码探测出「这台设备存在且属于别人」。
     */
    private void requireControlPermission(Device device) {
        UserRole role = LoginContext.requireRole();
        if (role == UserRole.COMMUNITY_ADMIN) {
            return;
        }
        if (role == UserRole.ELDER) {
            Long myElderId = requireCurrentElderProfileId(device);
            if (!myElderId.equals(device.getElderId())) {
                throw BusinessException.notFound("设备不存在");
            }
            return;
        }
        throw BusinessException.forbidden("服务人员不能控制设备");
    }

    /** 读权限比控制权限宽：老人可见自己的，服务人员不可见设备 */
    private void requireViewPermission(Device device) {
        UserRole role = LoginContext.requireRole();
        if (role == UserRole.COMMUNITY_ADMIN) {
            return;
        }
        if (role == UserRole.ELDER) {
            if (!requireCurrentElderProfileId(device).equals(device.getElderId())) {
                throw BusinessException.notFound("设备不存在");
            }
            return;
        }
        throw BusinessException.forbidden("服务人员不能查看设备档案");
    }

    /**
     * 取当前登录者的老人档案 ID。
     *
     * <p>与订单模块一致的约定：一个账号可能有多个老人档案，
     * 设备控制按第一个档案处理。若将来支持多档案，这里需要显式选档案，
     * 不靠隐式取第一个。
     */
    private Long requireCurrentElderProfileId(Device device) {
        Long userId = LoginContext.requireUserId();
        ElderProfile profile = elderProfileMapper.findByUserId(userId);
        if (profile == null) {
            throw BusinessException.forbidden("当前账号没有老人档案，无法操作设备");
        }
        return profile.getId();
    }

    private void requireAdmin() {
        if (LoginContext.requireRole() != UserRole.COMMUNITY_ADMIN) {
            throw BusinessException.forbidden("仅社区管理员可维护设备");
        }
    }

    /**
     * 模拟器总开关。
     *
     * <p>配置未启用时直接拒绝而不是静默成功：让人以为指令已下发、
     * 实际没有任何执行者，是最难排查的一类问题。
     */
    private void requireSimulatorEnabled() {
        if (!simulatorProperties.isEnabled()) {
            throw BusinessException.forbidden("设备模拟器未启用（配置项 care.simulator.enabled=false）");
        }
    }

    private String hash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("摘要算法不可用", e);
        }
    }
}
