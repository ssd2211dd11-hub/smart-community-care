import request from '@/utils/request'

/**
 * 服务预约接口。
 *
 * <p>创建预约时<b>不传 userId、不传金额</b>：归属取自会话，
 * 金额由后端按服务当前价格做快照。传入反而会被后端忽略或拒绝。
 */

/** 上架中的服务目录 */
export function listServices() {
  return request.get('/services')
}

/** 某服务未来的开放时段，含剩余名额 */
export function listSlots(serviceId) {
  return request.get(`/services/${serviceId}/slots`)
}

/**
 * 创建预约。
 *
 * @param {string} slotId 时段 ID
 * @param {object} payload { contactName, contactPhone, address, requestId }
 *        requestId 由调用方为「一次提交」生成，重试沿用同一个——
 *        它是幂等键，用户主动再约一次才换新的
 */
export function createOrder(payload) {
  return request.post('/service-orders', payload)
}

/** 我的订单 */
export function listMyOrders() {
  return request.get('/service-orders')
}

/** 订单详情：后端按角色限定可见范围 */
export function getOrder(orderId) {
  return request.get(`/service-orders/${orderId}`)
}

/**
 * 读取订单页面安全详情，补充责任人姓名，ID使用字符串，不返回请求键或摘要。
 * 后端进行对象授权；无权访问及不存在均返回404。
 */
export function getOrderView(orderId) {
  return request.get(`/service-orders/${orderId}/view`)
}

/**
 * 我的订单（老人），服务端分页。
 *
 * <p>翻页换不掉归属——elderId 由会话决定，不接受参数传入。
 */
export function listMyOrdersPaged(params) {
  return request.get('/service-orders/mine/paged', { params })
}

/**
 * 分派给我的任务（服务人员），服务端分页。
 *
 * <p>翻页换不掉归属——staffId 由会话决定。
 */
export function listMyTasksPaged(params) {
  return request.get('/service-orders/my-tasks/paged', { params })
}

/**
 * 查询本人参与历史的脱敏摘要，转派后仍保留本人动作。
 * 不返回电话或地址，调用方不得额外读取当前详情补齐资料；历史摘要不提供操作入口。
 * @param {number} [limit] 条数上限；后端另有封顶，缺省 20
 */
export function listMyParticipations(limit) {
  return request.get('/service-orders/my-participations', { params: { limit } })
}

/** 取消预约：只有首次合法取消才释放名额 */
export function cancelOrder(orderId, reason, version) {
  return request.post(`/service-orders/${orderId}/cancel`, { reason, version })
}

/**
 * 生成请求幂等键。
 *
 * <p>用浏览器自带的随机源，不引第三方库。同一笔提交在重试时
 * 必须复用同一个值，所以这个函数在「用户点提交」时调用一次，
 * 存在组件状态里，而不是每次发送请求时重新生成。
 */
export function newRequestId() {
  if (window.crypto?.randomUUID) {
    return window.crypto.randomUUID()
  }
  // 兜底：时间戳 + 随机串
  return `req-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/** 管理员视角的订单列表（服务端分页，支持按状态筛选） */
export function listAllOrders(params) {
  return request.get('/service-orders/admin/all', { params })
}

/** 可分派的服务人员（仅 id、姓名、角色），供分派/转派弹窗使用 */
export function listAssignableStaff() {
  return request.get('/users/assignable-staff')
}

/** 分派给我的任务（服务人员视角） */
export function listMyTasks() {
  return request.get('/service-orders/my-tasks')
}

export const listClaimableOrders = params => request.get('/service-orders/claimable/paged', { params })
export const claimOrder = (orderId, version) => request.post(`/service-orders/${orderId}/claim`, { version })

/** 管理员分派给服务人员 */
export function assignOrder(orderId, staffId, reason, version) {
  return request.post(`/service-orders/${orderId}/assign`, { staffId, reason, version })
}

/** 管理员转派给另一名服务人员，原因必填 */
export function transferOrder(orderId, newStaffId, reason, version) {
  return request.post(`/service-orders/${orderId}/transfer`, { newStaffId, reason, version })
}

/** 当前受派人接单 */
export function acceptOrder(orderId, reason, version) {
  return request.post(`/service-orders/${orderId}/accept`, { reason, version })
}

/** 当前受派人拒接，原因必填 */
export function rejectOrder(orderId, reason, version) {
  return request.post(`/service-orders/${orderId}/reject`, { reason, version })
}

/**
 * 开始服务（仅当前受派人，不得早于预约开始时间）。
 *
 * <p><b>version 必须带页面已读取的版本</b>：缺失返回 400，过期返回 409。
 */
export function startService(orderId, version) {
  return request.post(`/service-orders/${orderId}/start`, { version })
}

/** 提交服务结果。version 必须带，理由同 {@link startService} */
export function submitResult(orderId, result, version) {
  return request.post(`/service-orders/${orderId}/submit-result`, { result, version })
}

/**
 * 老人确认完成。
 *
 * <p>与 {@link startService} 同理：version 必须带，否则并发确认形同虚设。
 */
export function confirmOrder(orderId, version) {
  return request.post(`/service-orders/${orderId}/confirm`, { version })
}

/** 管理员代确认，原因必填 */
export function confirmAsAdmin(orderId, reason, version) {
  return request.post(`/service-orders/${orderId}/confirm-as-admin`, { reason, version })
}

/** 反馈未完成事项，订单回到服务中 */
export function returnOrder(orderId, result, version) {
  return request.post(`/service-orders/${orderId}/return`, { result, version })
}

/** 管理员异常终止，原因必填 */
export function terminateOrder(orderId, reason, version) {
  return request.post(`/service-orders/${orderId}/terminate`, { reason, version })
}

/** 提交评价（仅已完成订单的所属老人可提交，一单一评） */
export function createReview(orderId, payload) {
  return request.post(`/service-orders/${orderId}/review`, payload)
}

/** 某订单的评价 */
export function getReview(orderId) {
  return request.get(`/service-orders/${orderId}/review`)
}

/** 我给出的评价 */
export function listMyReviews() {
  return request.get('/service-orders/reviews/mine')
}

/** 当前页订单的本人评价；ID保持字符串，最多100笔。 */
export function listMyReviewsForOrders(orderIds) {
  return request.get('/service-orders/reviews/mine/for-orders', { params: { orderIds: orderIds.join(',') } })
}

/** 本人服务所得的评价 */
export function listReceivedReviews() {
  return request.get('/service-orders/reviews/received')
}
