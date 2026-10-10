import request from '@/utils/request'

/**
 * 设备与模拟控制接口。
 *
 * <p>后端控制指令是「提交 → 待执行 → 终态」两段式，
 * 所以 submitCommand 成功只表示指令已保存。页面必须靠轮询
 * listCommands 看结果，不能在提交后直接显示「已开启」。
 */

/** 设备列表。管理员看全部，老人只看自己的，后端按登录角色收窄 */
export function listDevices() {
  return request.get('/devices')
}
export const listDevicesPaged = params => request.get('/devices/paged', {params})
export const listDeviceCommandsPaged = (id,params) => request.get(`/devices/${id}/commands/paged`, {params})

/** 总引擎状态（管理员），不依赖已有设备数量。 */
export function getSimulatorStatus() {
  return request.get('/devices/simulator-status')
}

/** 可选归属的老人（管理员建档时用），返回 elder_profile.id */
export function listAssignableElders() {
  return request.get('/devices/assignable-elders')
}

/** 新建设备（管理员） */
export function createDevice(payload) {
  return request.post('/devices', payload)
}

/** 修改设备档案（管理员）。设备编号不可改 */
export function updateDevice(deviceId, payload) {
  return request.put(`/devices/${deviceId}`, payload)
}

/** 启动虚拟设备（管理员） */
export function startSimulator(deviceId) {
  return request.post(`/devices/${deviceId}/simulator/start`)
}

/** 停止虚拟设备（管理员或设备所属老人） */
export function stopSimulator(deviceId) {
  return request.post(`/devices/${deviceId}/simulator/stop`)
}

/** 切换模拟执行场景（管理员） */
export function setSimulationMode(deviceId, mode) {
  return request.post(`/devices/${deviceId}/simulation-mode`, { mode })
}

/** 下发开关指令，返回的是指令状态，不是执行结果 */
export function submitCommand(deviceId, targetPowerStatus, requestId) {
  return request.post(`/devices/${deviceId}/commands`, {
    targetPowerStatus,
    requestId
  })
}

/** 某设备的指令历史 */
export function listDeviceCommands(deviceId) {
  return request.get(`/devices/${deviceId}/commands`)
}

/** 全部设备的指令历史（管理员） */
export function listAllCommands() {
  return request.get('/devices/commands')
}
