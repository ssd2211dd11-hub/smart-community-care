package org.imnu.smartcommunitycare.order.service;

import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.elder.mapper.ElderProfileMapper;
import org.imnu.smartcommunitycare.order.mapper.ServiceReviewMapper;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.entity.ServiceReview;
import org.imnu.smartcommunitycare.order.model.enums.OrderStatus;
import org.imnu.smartcommunitycare.order.model.request.CreateReviewRequest;
import org.imnu.smartcommunitycare.order.mapper.ServiceOrderMapper;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * 服务评价。
 *
 * <p><b>规则</b>：只有订单所属老人可以对 COMPLETED 订单评价，1~5 分，一单一评，
 * 提交后不可修改，管理员不代写评分。评价<b>不改变订单终态、名额或完成时间</b>——
 * 评价是服务后的反馈，不是履约的一部分。
 *
 * <p>不做评价编辑/删除/审核，也不做运营报表。首版没有审核流程，
 * 加了就是给自己挖坑。
 */
@Service
@RequiredArgsConstructor
public class OrderReviewService {

    private final ServiceReviewMapper reviewMapper;
    private final ServiceOrderMapper orderMapper;
    private final ElderProfileMapper elderMapper;
    private final Clock clock;

    /**
     * 提交评价。
     *
     * <p>并发靠唯一约束 {@code uk_service_review_order} 兜底：
     * 同一订单并发评价，只有一个 insert 成功，其余被数据库挡住。
     * 重复提交返回冲突，不覆盖原评分——
     * 覆盖等于让用户改了答案后数据就变，会让「服务到底几分」变得不可追溯。
     */
    @Transactional(rollbackFor = Exception.class)
    public ServiceReview create(Long orderId, CreateReviewRequest request) {
        Long elderId = requireCurrentElder();

        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null || !elderId.equals(order.getElderId())) {
            // 不区分不存在与无权
            throw BusinessException.notFound("订单不存在");
        }
        if (!OrderStatus.COMPLETED.name().equals(order.getStatus())) {
            throw BusinessException.conflict(
                    "只有已完成的订单可以评价，当前状态「"
                            + OrderStatus.from(order.getStatus()).label() + "」");
        }
        if (request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            throw BusinessException.badRequest("评分需在 1 到 5 之间");
        }

        ServiceReview existing = reviewMapper.findByOrder(orderId);
        if (existing != null) {
            throw BusinessException.conflict("该订单已评价，不能重复评价或修改");
        }

        ServiceReview review = new ServiceReview();
        review.setOrderId(orderId);
        review.setElderId(elderId);
        review.setRating(request.rating());
        review.setContent(blankToNull(request.content()));
        review.setCreatedAt(LocalDateTime.now(clock));

        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // 与顺序重复一致：不覆盖原评分，并明确返回冲突。
            throw BusinessException.conflict("该订单已评价，不能重复评价或修改");
        }
        return review;
    }

    /** 某订单的评价 */
    public ServiceReview getByOrder(Long orderId) {
        return reviewMapper.findByOrder(orderId);
    }

    /** 我给出的评价 */
    public List<ServiceReview> listMine() {
        return reviewMapper.findByElder(requireCurrentElder());
    }

    /** 当前列表页关联的评价；订单标识不能扩大当前老人可见范围。 */
    @Transactional(readOnly = true)
    public List<ServiceReview> mineForOrders(List<Long> orderIds) {
        Long elderId = requireCurrentElder();
        if (orderIds == null || orderIds.isEmpty() || orderIds.size() > 100
                || orderIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw BusinessException.badRequest("请提供1到100个有效订单标识");
        }
        return reviewMapper.selectList(Wrappers.<ServiceReview>lambdaQuery()
                .eq(ServiceReview::getElderId, elderId).in(ServiceReview::getOrderId, orderIds)
                .orderByDesc(ServiceReview::getCreatedAt).orderByDesc(ServiceReview::getId));
    }

    /** 本人服务所得的评价 */
    public List<ServiceReview> listReceived() {
        LoginContext.requireAnyRole(UserRole.SERVICE_STAFF);
        return reviewMapper.findByStaff(LoginContext.requireUserId());
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record ReceivedPage(List<ServiceReview> records,long total,int pageNum,int pageSize,long ratedCount,Double averageRating) {}
    @Transactional(readOnly=true)
    public ReceivedPage receivedPage(org.imnu.smartcommunitycare.common.api.PageQuery query) {
        LoginContext.requireAnyRole(UserRole.SERVICE_STAFF);Long staff=LoginContext.requireUserId();
        long total=reviewMapper.countFiltered(staff,null);
        return new ReceivedPage(reviewMapper.page(staff,null,query.safePageSize(),query.offset()),total,query.safePageNum(),query.safePageSize(),total,reviewMapper.averageRating(staff));
    }

    /** 全部评价，管理员查看 */
    public List<ServiceReview> listAll() {
        LoginContext.requireAnyRole(UserRole.COMMUNITY_ADMIN);
        return reviewMapper.findAll();
    }

    /**
     * 某服务人员服务的平均分。
     *
     * <p>只统计已评价的订单；没有评价时返回 null 而不是 0——
     * 「没人评价」和「平均 0 分」是两回事，混淆会让服务人员白背一星。
     */
    public Double averageRatingFor(Long staffId) {
        return reviewMapper.averageRating(staffId);
    }

    // ---------------- 内部 ----------------

    private Long requireCurrentElder() {
        LoginContext.requireAnyRole(UserRole.ELDER);
        Long userId = LoginContext.requireUserId();
        var profile = elderMapper.findByUserId(userId);
        if (profile == null) {
            throw BusinessException.badRequest("当前账号尚未建立老人档案");
        }
        return profile.getId();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
