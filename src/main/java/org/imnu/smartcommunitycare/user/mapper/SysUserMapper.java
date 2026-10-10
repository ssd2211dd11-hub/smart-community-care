package org.imnu.smartcommunitycare.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.user.model.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 账号数据访问。Mapper 即 DAO，不再包一层 Repository。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE username = #{username}")
    SysUser findByUsername(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE id = #{id} FOR UPDATE")
    SysUser findByIdForUpdate(@Param("id") Long id);

    @Select("SELECT id FROM sys_user WHERE role='COMMUNITY_ADMIN' ORDER BY id")
    java.util.List<Long> findAdministratorIds();

    // 用明确的主键点查按序锁行；避免在启用状态索引范围上锁定并改写状态。
    // 同类启停都使用这里，先锁完整管理员集合，再判断最后管理员。
    default java.util.List<SysUser> lockAdministrators() {
        return findAdministratorIds().stream().map(this::findByIdForUpdate)
                .filter(java.util.Objects::nonNull).toList();
    }
}
