import request from '@/utils/request'
export const listServicesPaged = params => request.get('/services/paged',{params})
export const listSlotsPaged = (id,params) => request.get(`/services/${id}/slots/paged`,{params})

/**
 * 服务目录与时段接口。
 *
 * <p>后端创建时段、调整容量、关闭时段都用 query 参数（@RequestParam），
 * 这里照实传 params，不放进请求体。
 */

/** 上架中的服务目录 */
export function listAssignableServices() {
  return request.get('/services')
}

/**
 * 全部服务（含已下架），**仅管理员**。
 *
 * <p>管理页必须能看到已下架项，否则下架后就再也点不到「上架」恢复。
 * 后端对 `all=true` 有角色校验，非管理员调用会得到 403。
 */
export function listAllServices() {
  return request.get('/services', { params: { all: true } })
}

/** 某服务未来的开放时段（含剩余名额），老人预约页用 */
export function listSlots(serviceId) {
  return request.get(`/services/${serviceId}/slots`)
}

/** 管理员查看某服务的全部时段（含已关闭、已过期），管理页用 */
export function listAllSlots(serviceId) {
  return request.get(`/services/${serviceId}/slots`, { params: { all: true } })
}

/** 新建服务 */
export function createServiceItem(data) {
  return request.post('/services', data)
}

/** 编辑服务 */
export function updateServiceItem(serviceId, data) {
  return request.put(`/services/${serviceId}`, data)
}

/** 下架服务：阻止新预约，历史订单不受影响 */
export function disableService(serviceId) {
  return request.put(`/services/${serviceId}/disable`)
}

/**
 * 恢复服务的新预约受理。
 * 先锁服务行，与下架及建单保持一致的锁顺序；重复调用幂等，已有订单和时段不变。
 */
export function enableService(serviceId) {
  return request.put(`/services/${serviceId}/enable`)
}

/**
 * 恢复时段受理，仅修改enabled；起止时间、容量、占用和已有订单保持不变，重复调用幂等。
 */
export function reopenSlot(slotId) {
  return request.put(`/services/slots/${slotId}/reopen`)
}

/** 新建预约时段 */
export function createSlot(serviceId, { startAt, endAt, capacity }) {
  return request.post(`/services/${serviceId}/slots`, null, {
    params: { startAt, endAt, capacity }
  })
}

/** 调整时段容量，不得低于已占用数 */
export function updateSlotCapacity(slotId, capacity) {
  return request.put(`/services/slots/${slotId}/capacity`, null, { params: { capacity } })
}

/** 关闭时段：不再受理新预约，已存在订单不受影响 */
export function closeSlot(slotId) {
  return request.put(`/services/slots/${slotId}/close`)
}
