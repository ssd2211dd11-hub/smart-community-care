import request from '@/utils/request'

/**
 * 健康记录接口。
 *
 * <p>路径里没有 userId：老人自己的记录由后端按会话确定归属，
 * 只有管理员代录才显式传 elderId。前端不传 userId，避免"改个参数就能查别人"。
 */

/** 录入健康记录。mode 为 CURRENT（当前测量）或 HISTORY（历史补录） */
export function createHealthRecord(data) {
  return request.post('/health-records', data)
}

/** 查询最近七天的记录 */
export function listHealthRecords(elderId) {
  return request.get('/health-records', elderId ? { params: { elderId } } : undefined)
}

/** 最近一条有效记录 */
export function getLatestHealthRecord(elderId) {
  return request.get('/health-records/latest', elderId ? { params: { elderId } } : undefined)
}

/** 七日概览：各指标样本数/均值/极值/命中次数 + 按天分组趋势 */
export function getHealthSummary(elderId) {
  return request.get('/health-records/summary', elderId ? { params: { elderId } } : undefined)
}

/** 作废一条记录，需填原因 */
export function voidHealthRecord(recordId, reason) {
  return request.post(`/health-records/${recordId}/void`, null, { params: { reason } })
}
