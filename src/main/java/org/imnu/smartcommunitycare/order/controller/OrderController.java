package org.imnu.smartcommunitycare.order.controller;

import org.imnu.smartcommunitycare.common.api.ApiResponse;
import org.imnu.smartcommunitycare.common.api.PageQuery;
import org.imnu.smartcommunitycare.common.exception.BusinessException;
import org.imnu.smartcommunitycare.common.security.LoginContext;
import org.imnu.smartcommunitycare.user.model.enums.UserRole;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrder;
import org.imnu.smartcommunitycare.order.model.response.OrderDetailResponse;
import org.imnu.smartcommunitycare.order.model.response.MyParticipationResponse;
import org.imnu.smartcommunitycare.order.model.entity.ServiceReview;
import org.imnu.smartcommunitycare.order.model.entity.ServiceOrderLog;
import org.imnu.smartcommunitycare.order.model.request.AssignStaffRequest;
import org.imnu.smartcommunitycare.order.model.request.CreateReviewRequest;
import org.imnu.smartcommunitycare.order.model.request.ConfirmOrderRequest;
import org.imnu.smartcommunitycare.order.model.request.CreateOrderRequest;
import org.imnu.smartcommunitycare.order.model.request.SubmitResultRequest;
import org.imnu.smartcommunitycare.order.model.request.TerminateOrderRequest;
import org.imnu.smartcommunitycare.order.model.request.TransferOrderRequest;
import org.imnu.smartcommunitycare.order.model.request.OrderActionRequest;
import org.imnu.smartcommunitycare.order.model.request.OrderVersionRequest;
import org.imnu.smartcommunitycare.order.service.OrderBookingService;
import org.imnu.smartcommunitycare.order.service.OrderFulfillmentService;
import org.imnu.smartcommunitycare.order.service.OrderServiceExecutionService;
import org.imnu.smartcommunitycare.order.service.OrderReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务预约接口。
 *
 * <p>动作路径用明确业务动词（cancel），<b>不提供 {@code PUT /{id}/status}
 * 这种接收任意状态的入口</b>——那会让前端绕过状态机约束。
 * 金额、服务名、归属、状态都由后端确定，请求里不接受。
 */
@RestController
@RequestMapping("/api/service-orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderBookingService bookingService;
    private final OrderFulfillmentService fulfillmentService;
    private final OrderServiceExecutionService executionService;
    private final OrderReviewService reviewService;

    @GetMapping("/claimable/paged")
    public ApiResponse<?> claimablePage(PageQuery query) {
        return ApiResponse.success("查询成功", fulfillmentService.claimablePage(query));
    }

    @PostMapping("/{orderId}/claim")
    public ApiResponse<ServiceOrder> claim(@PathVariable Long orderId,
                                          @Valid @RequestBody OrderVersionRequest request) {
        return ApiResponse.success("领取成功", fulfillmentService.claim(orderId, request.version()));
    }
    @GetMapping("/my-participations/paged")
    public ApiResponse<?> participationPage(PageQuery query) {
        return ApiResponse.success("查询成功",fulfillmentService.participationPage(query));
    }
    @GetMapping("/reviews/received/paged")
    public ApiResponse<?> receivedReviewsPage(PageQuery query) {
        return ApiResponse.success("查询成功",reviewService.receivedPage(query));
    }
    private final org.imnu.smartcommunitycare.order.service.OrderListService listService;

    /**
     * 管理员视角的订单列表（服务端分页，支持按状态筛选）。
     *
     * <p>与 {@code GET /api/service-orders}（老人看自己的）分开：
     * 管理员看全部，但同样走服务端分页与状态条件，不在浏览器过滤。
     */
    @GetMapping("/admin/all")
    public ApiResponse<PageQuery.PageResult<ServiceOrder>> listForAdmin(org.imnu.smartcommunitycare.order.model.request.OrderPageQuery query) {
        return ApiResponse.success("查询成功",listService.page(query,UserRole.COMMUNITY_ADMIN));
    }

    /**
     * 管理员分派订单给服务人员。
     *
     * <p>动作路径用业务动词，不提供 {@code PUT /{id}/status}
     * 这类接收任意状态的入口。
     */
    @PostMapping("/{orderId}/assign")
    public ApiResponse<ServiceOrder> assign(@PathVariable Long orderId,
                                           @Valid @RequestBody AssignStaffRequest request) {
        return ApiResponse.success("分派成功", fulfillmentService.assign(orderId, request));
    }

    /** 服务人员转派给另一人（原因必填） */
    @PostMapping("/{orderId}/transfer")
    public ApiResponse<ServiceOrder> transfer(@PathVariable Long orderId,
                                              @Valid @RequestBody TransferOrderRequest request) {
        return ApiResponse.success("转派成功", fulfillmentService.transfer(orderId, request));
    }

    /** 当前受派人接单 */
    @PostMapping("/{orderId}/accept")
    public ApiResponse<ServiceOrder> accept(@PathVariable Long orderId,
                                            @Valid @RequestBody OrderActionRequest request) {
        return ApiResponse.success("接单成功", fulfillmentService.accept(orderId, request));
    }

    /** 当前受派人拒接（原因必填） */
    @PostMapping("/{orderId}/reject")
    public ApiResponse<ServiceOrder> reject(@PathVariable Long orderId,
                                            @Valid @RequestBody OrderActionRequest request) {
        return ApiResponse.success("已拒接", fulfillmentService.reject(orderId, request));
    }

    /**
     * 开始服务。仅当前受派人可操作，且不得早于预约开始时间。
     */
    @PostMapping("/{orderId}/start")
    public ApiResponse<ServiceOrder> start(@PathVariable Long orderId,
                                           @Valid @RequestBody OrderVersionRequest request) {
        return ApiResponse.success("已开工",
                executionService.start(orderId, request.version()));
    }

    /** 提交服务结果（内容必填），进入待确认 */
    @PostMapping("/{orderId}/submit-result")
    public ApiResponse<ServiceOrder> submitResult(
            @PathVariable Long orderId,
            @Valid @RequestBody SubmitResultRequest request) {
        return ApiResponse.success("已提交，等待老人确认",
                executionService.submitResult(orderId, request));
    }

    /** 老人确认完成 */
    @PostMapping("/{orderId}/confirm")
    public ApiResponse<ServiceOrder> confirm(@PathVariable Long orderId,
                                             @Valid @RequestBody OrderVersionRequest request) {
        return ApiResponse.success("已确认完成",
                executionService.confirm(orderId, request.version()));
    }

    /** 管理员代确认（原因必填） */
    @PostMapping("/{orderId}/confirm-as-admin")
    public ApiResponse<ServiceOrder> confirmAsAdmin(
            @PathVariable Long orderId,
            @Valid @RequestBody ConfirmOrderRequest request) {
        return ApiResponse.success("已代确认完成", executionService.confirmByAdmin(orderId, request));
    }

    /** 老人反馈未完成事项，订单回到服务中 */
    @PostMapping("/{orderId}/return")
    public ApiResponse<ServiceOrder> sendBack(
            @PathVariable Long orderId,
            @Valid @RequestBody SubmitResultRequest request) {
        return ApiResponse.success("已退回，服务人员需重新处理",
                executionService.sendBack(orderId, request));
    }

    /** 管理员异常终止（原因必填），不算成功完成，名额不释放 */
    @PostMapping("/{orderId}/terminate")
    public ApiResponse<ServiceOrder> terminate(
            @PathVariable Long orderId,
            @Valid @RequestBody TerminateOrderRequest request) {
        return ApiResponse.success("已终止", executionService.terminate(orderId, request));
    }

    /**
     * 提交评价。只有订单所属老人可对已完成订单评价，一单一评。
     */
    @PostMapping("/{orderId}/review")
    public ApiResponse<ServiceReview> review(
            @PathVariable Long orderId,
            @Valid @RequestBody CreateReviewRequest request) {
        return ApiResponse.success("评价成功", reviewService.create(orderId, request));
    }

    /** 某订单的评价 */
    @GetMapping("/{orderId}/review")
    public ApiResponse<ServiceReview> getReview(@PathVariable Long orderId) {
        bookingService.detail(orderId);   // 复用详情做对象级授权
        return ApiResponse.success("查询成功", reviewService.getByOrder(orderId));
    }

    /** 我给出的评价（老人视角） */
    @GetMapping("/reviews/mine")
    public ApiResponse<List<ServiceReview>> myReviews() {
        return ApiResponse.success("查询成功", reviewService.listMine());
    }

    @GetMapping("/reviews/mine/for-orders")
    public ApiResponse<List<ServiceReview>> myReviewsForOrders(@RequestParam List<Long> orderIds) {
        return ApiResponse.success("查询成功", reviewService.mineForOrders(orderIds));
    }

    /** 本人服务所得的评价（服务人员视角） */
    @GetMapping("/reviews/received")
    public ApiResponse<List<ServiceReview>> receivedReviews() {
        return ApiResponse.success("查询成功", reviewService.listReceived());
    }

    /** 全部评价（管理员视角） */
    @GetMapping("/reviews/all")
    public ApiResponse<List<ServiceReview>> allReviews() {
        return ApiResponse.success("查询成功", reviewService.listAll());
    }

    /** 创建预约（扣名额 + 建单 + 写日志，同一事务） */
    @PostMapping
    public ApiResponse<ServiceOrder> create(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.success("预约成功", bookingService.create(request));
    }

    /** 分派给我的任务（服务人员视角） */
    @GetMapping("/my-tasks")
    public ApiResponse<List<ServiceOrder>> myTasks() {
        return ApiResponse.success("查询成功", fulfillmentService.listMyTasks());
    }

    /** 我的订单（老人视角） */
    @GetMapping
    public ApiResponse<List<ServiceOrder>> listMine() {
        return ApiResponse.success("查询成功", bookingService.listMine());
    }

    /**
     * 分页查询会话老人本人的订单，保留既有全量接口的响应契约。
     */
    @GetMapping("/mine/paged")
    public ApiResponse<PageQuery.PageResult<ServiceOrder>> listMinePaged(org.imnu.smartcommunitycare.order.model.request.OrderPageQuery query) {
        return ApiResponse.success("查询成功",listService.page(query,UserRole.ELDER));
    }

    /**
     * 分页查询会话人员的任务，保留既有my-tasks接口的响应契约。
     */
    @GetMapping("/my-tasks/paged")
    public ApiResponse<PageQuery.PageResult<ServiceOrder>> myTasksPaged(org.imnu.smartcommunitycare.order.model.request.OrderPageQuery query) {
        return ApiResponse.success("查询成功",listService.page(query,UserRole.SERVICE_STAFF));
    }

    /**
     * 查询本人实际参与的只读订单摘要，转派后仍保留，且不附当前电话与地址。
     * 该入口返回服务端封顶的最近记录，完整分页使用对应分页入口。
     */
    @GetMapping("/my-participations")
    public ApiResponse<List<MyParticipationResponse>> myParticipations(
            @RequestParam(required = false) Integer limit) {
        requireStaffRole();
        return ApiResponse.success("查询成功",
                fulfillmentService.listMyParticipations(limit == null ? 20 : limit));
    }

    /** 组装分页入参；缺省值与 {@link PageQuery} 自身默认值一致 */
    private PageQuery buildPageQuery(Integer pageNum, Integer pageSize) {
        PageQuery query = new PageQuery();
        query.setPageNum(pageNum == null ? 1 : pageNum);
        query.setPageSize(pageSize == null ? 20 : pageSize);
        return query;
    }

    /**
     * 本人任务分页入口的角色门禁。
     *
     * <p>原 {@code /my-tasks} 端点的角色校验在
     * {@code OrderFulfillmentService.requireCurrentStaff()}，
     * 注释写明「老人用自己的账号也能查这张表」。
     * 新端点走 {@code OrderBookingService}，那条校验不会经过，
     * 所以在这里补一道——<b>不能因为换了服务层就丢掉授权</b>。
     */
    private void requireStaffRole() {
        if (LoginContext.requireRole() != UserRole.SERVICE_STAFF) {
            throw BusinessException.forbidden("仅服务人员可查看本人任务");
        }
    }

    /**
     * 订单处理时间线。
     *
     * <p>可见范围与 {@link #detail} 一致：先做对象级授权，
     * 再返回该订单的日志。老人只能看自己订单的时间线，
     * 工作人员只能看分派给自己的；无权访问按 404 处理，不泄露订单是否存在。
     */
    @GetMapping("/{orderId}/logs")
    public ApiResponse<List<ServiceOrderLog>> logs(@PathVariable Long orderId) {
        bookingService.detail(orderId);   // 复用详情做权限校验，无权则抛 404
        return ApiResponse.success("查询成功", bookingService.listLogs(orderId));
    }

    /** 订单详情：按角色限定可见范围 */
    /**
     * 读取订单页面安全详情，包含当前责任人及页面需要的字段。
     * 复用对象授权，无权访问与不存在均返回404。
     */
    @GetMapping("/{orderId}/view")
    public ApiResponse<OrderDetailResponse> detailView(@PathVariable Long orderId) {
        return ApiResponse.success("查询成功", bookingService.detailView(orderId));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<ServiceOrder> detail(@PathVariable Long orderId) {
        return ApiResponse.success("查询成功", bookingService.detail(orderId));
    }

    /** 取消预约：只有首次合法取消才释放名额 */
    @PostMapping("/{orderId}/cancel")
    public ApiResponse<ServiceOrder> cancel(@PathVariable Long orderId,
                                           @Valid @RequestBody OrderActionRequest request) {
        return ApiResponse.success("已取消", bookingService.cancel(orderId, request.reason(), request.version()));
    }
}
