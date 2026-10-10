import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { submitCommand } from '@/api/device'

// 一次设备操作保存原设备、绝对目标与请求键；确认重试不能变成另一次开关。
export function useDeviceCommand() {
  const pending = ref(null)
  const busy = ref(false)
  async function send() {
    if (busy.value || !pending.value) return false
    busy.value = true
    const snapshot = pending.value
    try {
      await submitCommand(snapshot.deviceId, snapshot.target, snapshot.requestId)
      pending.value = null
      ElMessage.success('指令已保存，等待设备执行；保存成功不代表设备已动作')
      return true
    } catch (error) {
      const status = error.response?.status
      if (!status || status >= 500 || snapshot.uncertain) {
        pending.value = { ...snapshot, uncertain: true }
      } else {
        pending.value = null
      }
      return false
    } finally {
      busy.value = false
    }
  }
  function submit(deviceId, target) {
    if (busy.value || pending.value) return Promise.resolve(false)
    pending.value = { deviceId, target, requestId: crypto.randomUUID(), uncertain: false }
    return send()
  }
  function forget() {
    if (busy.value) return
    pending.value = null
    ElMessage.warning('已不再跟踪本次提交；这不会取消指令，已保存的指令仍可能执行。请查看指令历史。')
  }
  return { pending, busy, submit, retry: send, forget }
}
