package org.imnu.smartcommunitycare.catalog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.catalog.model.entity.ServiceItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 服务项目数据访问。
 */
@Mapper
public interface ServiceItemMapper extends BaseMapper<ServiceItem> {

    /** 上架中的服务，按展示顺序 */
    @Select("SELECT * FROM service_item WHERE enabled = 1 ORDER BY sort_order, id")
    List<ServiceItem> findEnabled();

    /**
     * 全部服务，<b>含已下架</b>，按展示顺序。
     *
     * <p><b>为什么不能用 {@code selectList(null)}</b>（2026-10-07 实测确认）：
     * 那条路径<b>会漏掉 {@code enabled = 0} 的行</b>。实测：库里存在
     * {@code enabled = 0} 的服务时，{@code selectList(null)} 返回<b>空列表</b>。
     *
     * <p>这正是「管理端下架后服务从列表消失、也就无法上架回来」的<b>后端根因</b>：
     * 页面加多少按钮都没用，数据根本查不出来。
     * （已用真实页面复现：点下架后接口返回 200、页面提示「已下架」，
     * 但管理列表随即变「暂无服务项目」，管理员再也点不到「上架」。）
     *
     * <p>这里用显式 SQL 绕开该行为，语义也与 {@link #findEnabled()} 对称清晰。
     */
    @Select("SELECT * FROM service_item ORDER BY sort_order, id")
    List<ServiceItem> findAllIncludingDisabled();

    /**
     * 锁定服务行。
     *
     * <p>创建与调整时段时要先锁服务，再检查时段是否重叠。
     * 统一锁顺序（服务 → 时段）避免两个管理员同时建时段时互相看不见对方刚插入的行。
     */
    @Select("SELECT * FROM service_item WHERE id = #{id} FOR UPDATE")
    ServiceItem findByIdForUpdate(@Param("id") Long id);
}
