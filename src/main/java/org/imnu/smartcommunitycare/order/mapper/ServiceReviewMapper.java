package org.imnu.smartcommunitycare.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceReview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 服务评价数据访问。
 */
@Mapper
public interface ServiceReviewMapper extends BaseMapper<ServiceReview> {

    /**
     * 某订单的评价。一单一评，唯一键保证最多一条。
     */
    @Select("SELECT * FROM service_review WHERE order_id = #{orderId}")
    ServiceReview findByOrder(@Param("orderId") Long orderId);

    /**
     * 我给出的评价（作为老人）。
     */
    @Select("SELECT * FROM service_review WHERE elder_id = #{elderId} ORDER BY created_at DESC")
    List<ServiceReview> findByElder(@Param("elderId") Long elderId);

    /**
     * 某服务人员实际服务所得的评价。
     *
     * <p>按完成订单的最终服务人员 staff_id 归属；历史受派人只保留参与摘要，
     * 不将最终服务评价分配给已转出的人员。
     */
    @Select("""
            SELECT r.* FROM service_review r
            JOIN service_order o ON o.id = r.order_id
            WHERE o.staff_id = #{staffId}
            ORDER BY r.created_at DESC
            """)
    List<ServiceReview> findByStaff(@Param("staffId") Long staffId);

    /** 全量评价，管理员查看 */
    @Select("SELECT * FROM service_review ORDER BY created_at DESC")
    List<ServiceReview> findAll();

    /**
     * 某服务人员服务的平均分，<b>只统计已评价的订单</b>。
     *
     * <p>用 COUNT(rating) 作分母而不是订单数——未评价不是 0 分，
     * 算进平均会把服务人员的分数凭空拉低。
     */
    @Select("""
            <script>
            SELECT AVG(r.rating) FROM service_review r
            JOIN service_order o ON o.id = r.order_id
            <where>
              <if test="staffId != null">AND o.staff_id = #{staffId}</if>
            </where>
            </script>
            """)
    Double averageRating(@Param("staffId") Long staffId);

    String PAGE_FILTER="""
            <where>
              <if test="staffId != null">AND o.staff_id=#{staffId}</if>
              <if test="elderId != null">AND r.elder_id=#{elderId}</if>
            </where>
            """;
    @Select("<script>SELECT r.* FROM service_review r JOIN service_order o ON o.id=r.order_id "+PAGE_FILTER+
            " ORDER BY r.created_at DESC,r.id DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<ServiceReview> page(@Param("staffId") Long staffId,@Param("elderId") Long elderId,@Param("limit") int limit,@Param("offset") long offset);
    @Select("<script>SELECT COUNT(*) FROM service_review r JOIN service_order o ON o.id=r.order_id "+PAGE_FILTER+"</script>")
    long countFiltered(@Param("staffId") Long staffId,@Param("elderId") Long elderId);
}
