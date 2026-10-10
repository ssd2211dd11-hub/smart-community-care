package org.imnu.smartcommunitycare.catalog.service;

import org.imnu.smartcommunitycare.catalog.mapper.ServiceItemMapper;
import org.imnu.smartcommunitycare.catalog.mapper.ServiceSlotMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceSlot;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务目录与时段维护。
 *
 * <p><b>统一锁顺序：服务行 → 时段行</b>。创建时段、调整容量、关闭时段
 * 都先锁服务再操作时段，避免两个管理员并发操作时互相看不见对方刚提交的变更。
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final ServiceItemMapper itemMapper;
    private final ServiceSlotMapper slotMapper;

    private final Clock clock;

    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public org.imnu.smartcommunitycare.common.api.PageQuery.PageResult<ServiceItem> pageItems(
            org.imnu.smartcommunitycare.catalog.model.request.CatalogPageQuery query,boolean all) {
        if(all) org.imnu.smartcommunitycare.common.security.LoginContext.requireAnyRole(org.imnu.smartcommunitycare.user.model.enums.UserRole.COMMUNITY_ADMIN);
        var filter=com.baomidou.mybatisplus.core.toolkit.Wrappers.<ServiceItem>lambdaQuery();
        if(!all)filter.eq(ServiceItem::getEnabled,true);
        else if(query.getEnabled()!=null)filter.eq(ServiceItem::getEnabled,query.getEnabled());
        if(query.getKeyword()!=null&&!query.getKeyword().isBlank())filter.like(ServiceItem::getName,query.getKeyword().trim());
        if(query.getCategory()!=null&&!query.getCategory().isBlank())filter.eq(ServiceItem::getCategory,query.getCategory());
        long total=itemMapper.selectCount(filter);
        var rows=itemMapper.selectList(filter.orderByDesc(ServiceItem::getCreatedAt).orderByDesc(ServiceItem::getId)
                .last("LIMIT "+query.safePageSize()+" OFFSET "+query.offset()));
        return org.imnu.smartcommunitycare.common.api.PageQuery.PageResult.of(rows,total,query.safePageNum(),query.safePageSize());
    }

    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public org.imnu.smartcommunitycare.common.api.PageQuery.PageResult<ServiceSlot> pageSlots(Long serviceId,
            org.imnu.smartcommunitycare.common.api.PageQuery query,boolean all) {
        if(all)org.imnu.smartcommunitycare.common.security.LoginContext.requireAnyRole(org.imnu.smartcommunitycare.user.model.enums.UserRole.COMMUNITY_ADMIN);
        var item=itemMapper.selectById(serviceId);
        if(item==null||!all&&!Boolean.TRUE.equals(item.getEnabled()))throw org.imnu.smartcommunitycare.common.exception.BusinessException.notFound("服务不存在或已下架");
        var filter=com.baomidou.mybatisplus.core.toolkit.Wrappers.<ServiceSlot>lambdaQuery().eq(ServiceSlot::getServiceId,serviceId);
        if(!all)filter.eq(ServiceSlot::getEnabled,true).gt(ServiceSlot::getStartAt,java.time.LocalDateTime.now(clock));
        long total=slotMapper.selectCount(filter);
        var rows=slotMapper.selectList(filter.orderByAsc(ServiceSlot::getStartAt).orderByAsc(ServiceSlot::getId)
                .last("LIMIT "+query.safePageSize()+" OFFSET "+query.offset()));
        return org.imnu.smartcommunitycare.common.api.PageQuery.PageResult.of(rows,total,query.safePageNum(),query.safePageSize());
    }

    // ---------- 服务项目 ----------

    public List<ServiceItem> listEnabled() {
        return itemMapper.findEnabled();
    }

    /**
     * 全部服务（含已下架），<b>仅管理员</b>。
     *
     * <p><b>权限放在后端</b>：控制器的 {@code all=true} 只是参数，
     * 真正的门禁在这里。少了这一道，任何登录用户都能
     * 用 {@code GET /api/services?all=true} 看到已下架服务——
     * 那正是下架要隐藏的信息。
     */
    public List<ServiceItem> listAll() {
        requireAdmin();
        // 用显式 SQL 而不是 selectList(null)：后者会漏掉 enabled=0 的行，
        // 详见 ServiceItemMapper.findAllIncludingDisabled 的注释。
        return itemMapper.findAllIncludingDisabled();
    }

    public ServiceItem requireEnabled(Long serviceId) {
        ServiceItem item = itemMapper.selectById(serviceId);
        if (item == null || !Boolean.TRUE.equals(item.getEnabled())) {
            throw BusinessException.notFound("服务不存在或已下架");
        }
        return item;
    }

    @Transactional(rollbackFor = Exception.class)
    public ServiceItem createItem(ServiceItem item) {
        requireAdmin();
        item.setId(null);
        if (item.getEnabled() == null) {
            item.setEnabled(true);
        }
        if (item.getSortOrder() == null) {
            item.setSortOrder(0);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        itemMapper.insert(item);
        return item;
    }

    @Transactional(rollbackFor = Exception.class)
    public ServiceItem updateItem(Long serviceId, ServiceItem update) {
        requireAdmin();
        // 编辑前锁定服务行，使读取与回写enabled处于同一锁区间，避免覆盖并发上下架。
        // 锁顺序与建单一致：服务 → 时段。
        ServiceItem existing = itemMapper.findByIdForUpdate(serviceId);
        if (existing == null) {
            throw BusinessException.notFound("服务不存在");
        }
        ServiceItem target = new ServiceItem();
        target.setId(serviceId);
        target.setName(update.getName() != null ? update.getName() : existing.getName());
        target.setCategory(update.getCategory() != null ? update.getCategory() : existing.getCategory());
        target.setDescription(update.getDescription());
        target.setPrice(update.getPrice() != null ? update.getPrice() : existing.getPrice());
        target.setProvider(update.getProvider());
        target.setPhone(update.getPhone());
        target.setEnabled(update.getEnabled() != null ? update.getEnabled() : existing.getEnabled());
        target.setSortOrder(update.getSortOrder() != null ? update.getSortOrder() : existing.getSortOrder());
        target.setUpdatedAt(LocalDateTime.now(clock));
        itemMapper.updateById(target);
        return target;
    }

    /**
     * 下架服务。
     *
     * <p>下架只阻止新预约，**不取消已有订单**——已经约好的服务还要履约。
     */
    @Transactional(rollbackFor = Exception.class)
    public void disableItem(Long serviceId) {
        requireAdmin();
        // 读取并锁定服务行后再修改enabled，锁顺序与建单一致：服务 → 时段。
        ServiceItem locked = itemMapper.findByIdForUpdate(serviceId);
        if (locked == null) {
            throw BusinessException.notFound("服务不存在");
        }
        ServiceItem target = new ServiceItem();
        target.setId(serviceId);
        target.setEnabled(false);
        target.setUpdatedAt(LocalDateTime.now(clock));
        itemMapper.updateById(target);
    }

    /**
     * 恢复服务的新预约受理。
     * 先锁服务行，与下架及建单保持一致的锁顺序；重复调用幂等，已有订单和时段不变。
     */
    @Transactional(rollbackFor = Exception.class)
    public void enableItem(Long serviceId) {
        requireAdmin();
        // 锁顺序与 disableItem、建单路径一致：服务 → 时段。
        // 这里只改服务行、不碰时段行，但同样先取服务行锁，
        // 避免与「下架」交错时两个事务各写一半。
        ServiceItem locked = itemMapper.findByIdForUpdate(serviceId);
        if (locked == null) {
            throw BusinessException.notFound("服务不存在");
        }
        if (Boolean.TRUE.equals(locked.getEnabled())) {
            return;   // 已上架，幂等返回
        }
        ServiceItem target = new ServiceItem();
        target.setId(serviceId);
        target.setEnabled(true);
        target.setUpdatedAt(LocalDateTime.now(clock));
        itemMapper.updateById(target);
    }

    // ---------- 时段 ----------

    /**
     * 创建时段。
     *
     * <p>校验顺序：先锁服务行，再查重叠。两步必须在同一事务里，
     * 否则两个管理员同时建重叠时段时都会查到"没有重叠"。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceSlot createSlot(Long serviceId, LocalDateTime startAt, LocalDateTime endAt,
                                  int capacity) {
        requireAdmin();

        if (startAt == null || endAt == null) {
            throw BusinessException.badRequest("请填写时段开始与结束时间");
        }
        if (!endAt.isAfter(startAt)) {
            throw BusinessException.badRequest("结束时间必须晚于开始时间");
        }
        if (capacity <= 0) {
            throw BusinessException.badRequest("容量必须为正整数");
        }
        if (!startAt.isAfter(LocalDateTime.now(clock))) {
            throw BusinessException.badRequest("时段开始时间必须晚于当前时间");
        }

        // 锁服务行：统一锁顺序，且保证服务存在
        ServiceItem service = itemMapper.findByIdForUpdate(serviceId);
        if (service == null) {
            throw BusinessException.notFound("服务不存在");
        }

        List<ServiceSlot> overlapping =
                slotMapper.findOverlapping(serviceId, startAt, endAt);
        if (!overlapping.isEmpty()) {
            throw BusinessException.conflict("该时段与已有时段重叠");
        }

        ServiceSlot slot = new ServiceSlot();
        slot.setServiceId(serviceId);
        slot.setStartAt(startAt);
        slot.setEndAt(endAt);
        slot.setCapacity(capacity);
        slot.setOccupiedCount(0);
        slot.setEnabled(true);
        slot.setCreatedAt(LocalDateTime.now(clock));
        slot.setUpdatedAt(LocalDateTime.now(clock));
        slotMapper.insert(slot);
        return slot;
    }

    /**
     * 调整容量。
     *
     * <p>容量<b>不得低于已占用数</b>——把容量从 3 改成 1 而已占 2 个，
     * 会直接违反数据库的 CHECK 约束。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceSlot updateCapacity(Long slotId, int capacity) {
        requireAdmin();
        if (capacity <= 0) {
            throw BusinessException.badRequest("容量必须为正整数");
        }
        ServiceSlot slot = lockSlot(slotId);
        if (capacity < slot.getOccupiedCount()) {
            throw BusinessException.conflict(
                    "容量不能小于已占用名额（当前已占用 " + slot.getOccupiedCount() + "）");
        }
        ServiceSlot target = new ServiceSlot();
        target.setId(slotId);
        target.setCapacity(capacity);
        target.setUpdatedAt(LocalDateTime.now(clock));
        slotMapper.updateById(target);
        return slotMapper.selectById(slotId);
    }

    /**
     * 关闭时段受理。
     *
     * <p>只影响新预约，<b>不动开始与结束时间</b>——已有预约还要按原时间履约。
     */
    @Transactional(rollbackFor = Exception.class)
    public void closeSlot(Long slotId) {
        requireAdmin();
        lockSlot(slotId);
        ServiceSlot target = new ServiceSlot();
        target.setId(slotId);
        target.setEnabled(false);
        target.setUpdatedAt(LocalDateTime.now(clock));
        slotMapper.updateById(target);
    }

    /**
     * 恢复时段受理，仅修改enabled；起止时间、容量、占用和已有订单保持不变，重复调用幂等。
     */
    @Transactional(rollbackFor = Exception.class)
    public void reopenSlot(Long slotId) {
        requireAdmin();
        // 走 lockSlot：服务行 → 时段行，与 closeSlot / updateCapacity /
        // 建单路径顺序一致。不自己写一套加锁逻辑，避免顺序分叉。
        ServiceSlot locked = lockSlot(slotId);
        if (Boolean.TRUE.equals(locked.getEnabled())) {
            return;   // 已开放，幂等返回
        }
        ServiceSlot target = new ServiceSlot();
        target.setId(slotId);
        target.setEnabled(true);
        target.setUpdatedAt(LocalDateTime.now(clock));
        slotMapper.updateById(target);
    }

    /** 老人浏览：某服务未来的开放时段 */
    public List<ServiceSlot> listFutureOpenSlots(Long serviceId) {
        requireEnabled(serviceId);
        return slotMapper.findFutureOpen(serviceId, LocalDateTime.now(clock));
    }

    /** 管理员查看某服务的全部时段 */
    public List<ServiceSlot> listSlots(Long serviceId) {
        requireEnabled(serviceId);
        return slotMapper.findByService(serviceId);
    }

    /**
     * 按统一顺序锁定：先读时段拿到所属服务（不加锁），锁服务行，再锁时段行。
     *
     * <p>第一次读时段只是为了拿到 serviceId，不能一开始就锁时段——
     * 那会与"创建时段时先锁服务"的顺序冲突，形成死锁。
     */
    private ServiceSlot lockSlot(Long slotId) {
        ServiceSlot snapshot = slotMapper.selectById(slotId);
        if (snapshot == null) {
            throw BusinessException.notFound("时段不存在");
        }
        itemMapper.findByIdForUpdate(snapshot.getServiceId());
        ServiceSlot locked = slotMapper.findByIdForUpdate(slotId);
        if (locked == null) {
            throw BusinessException.notFound("时段不存在");
        }
        return locked;
    }

    private void requireAdmin() {
        if (LoginContext.requireRole() != UserRole.COMMUNITY_ADMIN) {
            throw BusinessException.forbidden("仅社区管理员可维护服务与时段");
        }
    }
}
