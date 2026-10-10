<template>
  <div class="page">
    <el-alert type="warning" :closable="false" show-icon class="notice">
      <template #title>
        您可启动或停止自己的模拟设备，并控制电灯、空调和窗帘开关。状态来自自动心跳和指令执行；设备登记与归属由社区维护。
      </template>
    </el-alert>

    <el-alert v-if="pending" type="warning" :closable="false" title="本次提交尚未确认。重试沿用原设备、目标和请求标识，不会重复下发。">
      <el-button :loading="busy" @click="retryCommand">确认原提交</el-button>
      <el-button :disabled="busy" @click="forget">不再跟踪</el-button>
    </el-alert>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false"><el-button link @click="loadAll()">重试</el-button></el-alert>
    <el-empty v-else-if="!loading && !devices.length" description="还没有分配给您的设备" />
    <!--
      顶部汇总提示只在「全部都不可控」时出现。
      部分不可控时每张卡片自己写了原因（controlBlockedReason），
      再加一条全局提示会与卡片里的说明重复，还容易让人以为整页都不能用。
    -->
    <el-alert
      v-else-if="!loading && devices.length && allBlocked"
      type="info"
      :closable="false"
      show-icon
      title="设备当前不可控制。未运行的设备可点击「启动设备」；已停用设备请联系社区管理员。"
    />

    <div v-for="d in devices" :key="d.id" class="dev-card">
      <div class="dev-head">
        <div>
          <div class="dev-name">{{ d.deviceName }}</div>
          <div class="muted">{{ TYPE_LABELS[d.deviceType] || d.deviceType }} · {{ d.location || '未标注位置' }}</div>
        </div>
        <el-tag :type="ONLINE_TAG[d.onlineStatus]">{{ ONLINE_LABELS[d.onlineStatus] }}</el-tag>
      </div>
      <div class="device-running">
        <span class="muted">模拟运行：{{d.simulatorRunning?'运行中':'已停止'}}</span>
        <el-button size="small" :loading="changingDevice===d.id" :disabled="!d.enabled || busy || !!pending || !!changingDevice" @click="toggleRunning(d)">{{d.simulatorRunning?'停止设备':'启动设备'}}</el-button>
      </div>

      <div class="dev-body">
        <div class="state">
          <div class="state-label">当前状态</div>
          <div class="state-value">
            <el-tag v-if="d.powerStatus" :type="d.powerStatus === 'ON' ? 'success' : 'info'" size="large">
              {{ d.powerStatus === 'ON' ? '已开启' : '已关闭' }}
            </el-tag>
            <span v-else class="muted">设备尚未上报</span>
          </div>
        </div>

        <div class="action">
          <el-switch
            :model-value="d.powerStatus === 'ON'"
            :disabled="!d.controlable || busy || !!pending || !!changingDevice || lastCommandFor(d)?.status === 'PENDING'"
            inline-prompt
            active-text="开"
            inactive-text="关"
            @change="onToggle(d, $event)"
          />
          <div class="muted control-hint">
            {{ d.controlable ? '开关已提交后由设备执行，稍后刷新查看结果' : d.controlBlockedReason }}
          </div>
        </div>
      </div>

      <div v-if="lastCommandFor(d)" class="last-cmd">
        最近指令：
        <el-tag :type="STATUS_TAG[lastCommandFor(d).status]" size="small">
          {{ STATUS_LABELS[lastCommandFor(d).status] }}
        </el-tag>
        <span class="muted">
          目标{{ lastCommandFor(d).targetPowerStatus === 'ON' ? '开启' : '关闭' }}
          · {{ formatTime(lastCommandFor(d).createdAt) }}
        </span>
      </div>
    </div>

    <el-button v-if="devices.length" :loading="loading" class="refresh" @click="loadAll">
      刷新状态
    </el-button>
    <ListPagination :page="page" :size="size" :total="total" :loading="loading" @page="changePage" @size="changeSize"/>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listDeviceCommandsPaged, listDevicesPaged, startSimulator, stopSimulator } from '@/api/device'
import ListPagination from '@/components/ListPagination.vue'
import { useDeviceCommand } from '@/composables/useDeviceCommand'

const TYPE_LABELS = { LIGHT: '电灯', AIRCON: '空调', CURTAIN: '窗帘' }
const ONLINE_LABELS = { ONLINE: '在线', OFFLINE: '离线', DISABLED: '已停用' }
const ONLINE_TAG = { ONLINE: 'success', OFFLINE: 'info', DISABLED: 'danger' }
const STATUS_LABELS = {
  PENDING: '待执行',
  SUCCEEDED: '成功',
  FAILED: '失败',
  TIMED_OUT: '结果未确认',
  CANCELLED: '已取消'
}
const STATUS_TAG = {
  PENDING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  TIMED_OUT: 'info',
  CANCELLED: 'info'
}

const loading = ref(false)
const changingDevice = ref(null)
const { pending, busy, submit, retry, forget } = useDeviceCommand()
let pollTimer
let activeLoads = 0
let deviceLoadId = 0
const devices = ref([])
const page=ref(1),size=ref(20),total=ref(0),loadError=ref('')
/** deviceId → 指令列表。老人只看自己的设备，这里按设备分开存 */
const commandMap = ref({})

async function toggleRunning(device) {
  if (changingDevice.value || busy.value || pending.value) return
  changingDevice.value = device.id
  try {
    if (device.simulatorRunning) await stopSimulator(device.id)
    else await startSimulator(device.id)
    ElMessage.success(device.simulatorRunning ? '设备已停止，未完成指令已取消' : '设备已启动')
    await loadAll()
  } catch (e) { ElMessage.error(e?.response?.data?.message || e?.message || '设备运行状态更新失败') }
  finally { changingDevice.value = null }
}

async function loadAll(showLoading = true, background = false) {
  if (background && activeLoads > 0) return
  const requestId = ++deviceLoadId
  const params = { pageNum: page.value, pageSize: size.value }
  activeLoads += 1
  loading.value = !!showLoading
  try {
    loadError.value=''
    let res=await listDevicesPaged(params)
    if (requestId !== deviceLoadId) return
    const last=Math.max(1,Math.ceil(res.data.total/params.pageSize))
    if(!res.data.records.length&&params.pageNum>last){res=await listDevicesPaged({pageNum:last,pageSize:params.pageSize})}
    if (requestId !== deviceLoadId) return
    devices.value=res.data.records;page.value=res.data.pageNum;total.value=res.data.total
    for (const d of devices.value) {
      if (requestId !== deviceLoadId) break
      await loadCommands(d.id, requestId)
    }
  } catch (e) {
    if (requestId === deviceLoadId) loadError.value=e?.response?.data?.message||'设备读取失败，请重试'
  } finally {
    activeLoads -= 1
    if (requestId === deviceLoadId) loading.value = false
  }
}
function changePage(value){page.value=value;return loadAll()}
function changeSize(value){size.value=value;return changePage(1)}

async function loadCommands(deviceId, requestId = deviceLoadId) {
  try {
    const res = await listDeviceCommandsPaged(deviceId,{pageNum:1,pageSize:1})
    if (requestId !== deviceLoadId) return
    commandMap.value = { ...commandMap.value, [deviceId]: res.data.records }
  } catch (e) {
    if (requestId === deviceLoadId) ElMessage.error('指令记录未能加载，请刷新确认；不能据此判断设备没有执行')
  }
}

/** 每台设备只看最新一条指令，页面不需要完整历史 */
/** 全部设备都不可控时才提示。空数组返回 false，避免空列表时误报 */
const allBlocked = computed(
  () => devices.value.length > 0 && devices.value.every((d) => !d.controlable)
)

function lastCommandFor(device) {
  const list = commandMap.value[device.id] || []
  return list.length ? list[0] : null
}

async function onToggle(device, checked) {
  if (changingDevice.value) return
  await submit(device.id, checked ? 'ON' : 'OFF')
  await loadAll(false)
}

async function retryCommand() {
  await retry()
  await loadAll(false)
}

function formatTime(value) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(5, 16)
}

onMounted(async () => {
  await loadAll()
  pollTimer = setInterval(() => loadAll(false, true), 3000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
.device-running { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.page {
  padding: 16px;
}

h2 {
  margin: 0 0 12px;
}

.notice {
  margin-bottom: 16px;
}

.dev-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 14px 16px;
  margin-bottom: 12px;
  background: var(--el-bg-color);
}

.dev-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.dev-name {
  font-size: 15px;
  font-weight: 600;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.dev-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.state-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.action {
  text-align: right;
}

.control-hint {
  margin-top: 4px;
  max-width: 260px;
  line-height: 1.6;
}

.last-cmd {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color-lighter);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.refresh {
  margin-top: 4px;
}
</style>
