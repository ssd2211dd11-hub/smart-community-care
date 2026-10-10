package org.imnu.smartcommunitycare.elder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.elder.model.entity.ElderProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 老人档案数据访问。Mapper 即 DAO，不再包一层 Repository。
 */
@Mapper
public interface ElderProfileMapper extends BaseMapper<ElderProfile> {

    /** 账号 ID 到档案的唯一映射（uk_elder_profile_user 保证唯一） */
    @Select("SELECT * FROM elder_profile WHERE user_id = #{userId}")
    ElderProfile findByUserId(@Param("userId") Long userId);

    String FILTER = """
            <where>
              <if test="keyword != null and keyword != ''">
                AND (u.username LIKE CONCAT('%',#{keyword},'%') OR u.real_name LIKE CONCAT('%',#{keyword},'%')
                     OR u.phone LIKE CONCAT('%',#{keyword},'%'))
              </if>
              <if test="enabled != null">AND u.enabled=#{enabled}</if>
            </where>
            """;

    @Select("<script>SELECT ep.id,ep.user_id,u.username,u.real_name,u.phone,u.enabled,ep.address,ep.emergency_contact "
            +"FROM elder_profile ep JOIN sys_user u ON u.id=ep.user_id "+FILTER
            +" ORDER BY ep.created_at DESC,ep.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    java.util.List<org.imnu.smartcommunitycare.elder.model.response.ElderSummary> page(
            @Param("keyword") String keyword,@Param("enabled") Boolean enabled,@Param("limit") int limit,@Param("offset") long offset);

    @Select("<script>SELECT COUNT(*) FROM elder_profile ep JOIN sys_user u ON u.id=ep.user_id "+FILTER+"</script>")
    long countFiltered(@Param("keyword") String keyword,@Param("enabled") Boolean enabled);

    @Select("SELECT * FROM elder_profile WHERE id=#{id} FOR UPDATE")
    ElderProfile findByIdForUpdate(@Param("id") Long id);
}
