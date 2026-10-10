import request from '@/utils/request'

/**
 * 告警事项接口。
 *
 * <p>动作路径都是明确业务动词，后端不提供"任意状态覆盖"的入口，
 * 前端也按同样的语义组织调用。
 */

/** 事项列表：后端按角色返回各自可见范围 */
export function listAlerts() {
  return request.get('/alert-events')
}

/** 事项详情，含处理时间线 */
export function getAlertDetail(eventId) {
  return request.get(`/alert-events/${eventId}`)
}

/** 管理员认领 */
export function claimAlert(eventId, version) {
  return request.post(`/alert-events/${eventId}/claim`, null, { params: { version } })
}

/** 管理员分派 */
export function assignAlert(eventId, assigneeId, reason, version) {
  return request.post(`/alert-events/${eventId}/assign`, null, {
    params: { assigneeId, reason, version }
  })
}

/** 受派人接收 */
export function acceptAlert(eventId, version) {
  return request.post(`/alert-events/${eventId}/accept`, null, { params: { version } })
}

/** 管理员转派 */
export function transferAlert(eventId, newHandlerId, reason, version) {
  return request.post(`/alert-events/${eventId}/transfer`, null, {
    params: { newHandlerId, reason, version }
  })
}

/** 关闭：必须给结果与说明 */
export function closeAlert(eventId, result, note, version) {
  return request.post(`/alert-events/${eventId}/close`, null, { params: { result, note, version } })
}

/** 撤销：仅未分派的主动求助 */
export function cancelAlert(eventId, reason, version) {
  return request.post(`/alert-events/${eventId}/cancel`, null, { params: { reason, version } })
}

/** 老人主动提交求助。requestId 由调用方为"一次提交"生成 */
export function submitHelpCall(data) {
  return request.post('/alert-events/help-calls', data)
}
