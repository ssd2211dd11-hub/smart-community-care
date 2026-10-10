<template>
  <div class="page">
    <div class="page-head">
      <div>
        <el-alert type="warning" :closable="false" show-icon class="sim-notice">
          <template #title>
            系统内置模拟设备，不接真实硬件。开关状态与在线状态均由平台内置模拟器生成。
          </template>
        </el-alert>
      </div>
      <el-button type="primary" @click="openCreate">新建设备</el-button>
    </div>

    <div class="simulator-state">
      <el-tag v-if="simulatorState" :type="simulatorState.globalEnabled ? 'success' : 'warning'">
        {{ simulatorState.globalEnabled ? '模拟引擎已开启' : '模拟引擎未开启' }}
      </el-tag>
      <span v-if="simulatorError">{{ simulatorError }}</span>
      <span v-else-if="simulatorState && !simulatorState.globalEnabled">当前不能启动设备或提交新指令，请联系运行管理员开启引擎。</span>
      <span v-else-if="simulatorState">设备需单独启动；在线状态取决于最近心跳。</span>
      <el-button link @click="load()">刷新状态</el-button>
    </div>

    <el-alert v-if="pending" type="warning" :closable="false" title="本次提交尚未确认。重试沿用原设备、目标和请求标识，不会重复下发。">
      <el-button :loading="busy" @click="retryCommand">确认原提交</el-button>
      <el-button :disabled="busy" @click="forget">不再跟踪</el-button>
    </el-alert>

    <el-form :inline="true" class="filter">
      <el-form-item label="设备类型">
        <el-select v-model="query.deviceType" clearable placeholder="全部" style="width: 130px">
          <el-option label="电灯" value="LIGHT" />
          <el-option label="空调" value="AIRCON" />
          <el-option label="窗帘" value="CURTAIN" />
        </el-select>
      </el-form-item>
      <el-form-item label="在线状态">
        <el-select v-model="query.onlineStatus" clearable placeholder="全部" style="width: 130px">
          <el-option label="在线" value="ONLINE" />
          <el-option label="离线" value="OFFLINE" />
          <el-option label="已停用" value="DISABLED" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="changeDevicePage(1)">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="deviceError" :title="deviceError" type="error" :closable="false"><el-button link @click="load()">重试</el-button></el-alert>
    <el-table v-else v-loading="loading" :data="devices" row-key="id">
      <el-table-column label="设备" min-width="160">
        <template #default="{ row }">
          <div class="dev-name">{{ row.deviceName }}</div>
          <div class="muted">{{ row.deviceNo }}</div>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ TYPE_LABELS[row.deviceType] || row.deviceType }}</template>
      </el-table-column>
      <el-table-column label="所属老人" width="110">
        <template #default="{ row }">
          {{ row.elderName || '未分配' }}
        </template>
      </el-table-column>
      <el-table-column label="位置" width="110" prop="location" />
      <el-table-column label="上报开关" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.powerStatus" :type="row.powerStatus === 'ON' ? 'success' : 'info'">
            {{ row.powerStatus === 'ON' ? '开启' : '关闭' }}
          </el-tag>
          <span v-else class="muted">未上报</span>
        </template>
      </el-table-column>
      <el-table-column label="在线状态" width="100">
        <template #default="{ row }">
          <el-tag :type="ONLINE_TAG[row.onlineStatus]">{{ ONLINE_LABELS[row.onlineStatus] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="模拟器" width="100">
        <template #default="{ row }">
          <el-tag :type="row.simulatorRunning ? 'success' : 'info'">
            {{ row.simulatorRunning ? '运行中' : '已停止' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="执行场景" width="150">
        <template #default="{ row }">
          <el-tag v-if="row.simulationMode !== 'NORMAL'" type="warning">
            {{ MODE_LABELS[row.simulationMode] }}
          </el-tag>
          <span v-else class="muted">正常</span>
        </template>
      </el-table-column>
      <el-table-column label="最近上报" width="160">
        <template #default="{ row }">
          <span v-if="row.lastSeenAt">{{ formatTime(row.lastSeenAt) }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="!row.simulatorRunning"
            link
            type="primary"
            :disabled="!row.enabled || !simulatorState?.globalEnabled"
            @click="doStartSimulator(row)"
          >启动</el-button>
          <el-button
            v-else
            link
            type="warning"
            @click="doStopSimulator(row)"
          >停止</el-button>
          <el-button link type="primary" @click="openControl(row)">控制</el-button>
          <el-button link type="info" @click="openDetail(row)">详情</el-button>
          <el-button link :type="row.enabled ? 'danger' : 'success'" @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <ListPagination :page="devicePage" :size="deviceSize" :total="deviceTotal" :loading="loading" @page="changeDevicePage" @size="changeDeviceSize"/>

    <!-- 新建设备 -->
    <el-dialog v-model="createVisible" title="新建设备" width="460px">
      <el-form :model="createForm" label-width="90px">
        <el-form-item label="设备编号" required>
          <el-input v-model="createForm.deviceNo" placeholder="如 LIVING-LIGHT-01" maxlength="32" />
          <div class="hint">字母、数字、下划线或短横线，创建后不可修改</div>
        </el-form-item>
        <el-form-item label="设备名称" required>
          <el-input v-model="createForm.deviceName" placeholder="如 客厅电灯" maxlength="50" />
        </el-form-item>
        <el-form-item label="设备类型" required>
          <el-select v-model="createForm.deviceType" style="width: 100%">
            <el-option label="电灯" value="LIGHT" />
            <el-option label="空调" value="AIRCON" />
            <el-option label="窗帘" value="CURTAIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属老人">
          <el-select v-model="createForm.elderId" clearable placeholder="暂不分配" style="width: 100%">
            <el-option
              v-for="e in elders"
              :key="e.id"
              :label="e.realName"
              :value="e.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="安装位置">
          <el-input v-model="createForm.location" placeholder="如 客厅" maxlength="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 控制面板 -->
    <el-dialog v-model="controlVisible" title="设备控制" width="500px">
      <div v-if="current" class="control-body">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="设备">{{ current.deviceName }}</el-descriptions-item>
          <el-descriptions-item label="位置">{{ current.location || '—' }}</el-descriptions-item>
          <el-descriptions-item label="上报状态">
            <el-tag v-if="current.powerStatus" :type="current.powerStatus === 'ON' ? 'success' : 'info'">
              {{ current.powerStatus === 'ON' ? '开启' : '关闭' }}
            </el-tag>
            <span v-else class="muted">未上报</span>
          </el-descriptions-item>
          <el-descriptions-item label="在线状态">
            <el-tag :type="ONLINE_TAG[current.onlineStatus]">
              {{ ONLINE_LABELS[current.onlineStatus] }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="!current.controlable"
          :title="current.controlBlockedReason"
          type="warning"
          :closable="false"
          show-icon
          class="control-block"
        />

        <el-form label-width="90px" class="control-form">
          <el-form-item label="模拟场景">
            <el-radio-group
              :model-value="current.simulationMode"
              :disabled="!current.simulatorRunning"
              @change="onModeChange"
            >
              <el-radio-button value="NORMAL">正常</el-radio-button>
              <el-radio-button value="FAIL_NEXT">下次失败</el-radio-button>
              <el-radio-button value="NO_ACK">不回报</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <div class="mode-hint">{{ MODE_HINTS[current.simulationMode] }}</div>

          <el-form-item label="目标状态">
            <el-switch
              :model-value="current.powerStatus === 'ON'"
              :disabled="!current.controlable || busy || !!pending || commands[0]?.status === 'PENDING'"
              inline-prompt
              active-text="开"
              inactive-text="关"
              @change="onSwitchChange"
            />
          </el-form-item>
        </el-form>

        <div class="control-tip">
          提交指令后设备不会立刻动作。下面的指令列表是真实执行结果，
          可点「刷新结果」查看。
        </div>
        <el-alert v-if="commandLoadError" :title="commandLoadError" type="error" :closable="false" />
        <div v-if="commands[0]" class="control-tip">
          最近指令：<el-tag :type="STATUS_TAG[commands[0].status]">{{ STATUS_LABELS[commands[0].status] }}</el-tag>
          · 目标{{ commands[0].targetPowerStatus === 'ON' ? '开启' : '关闭' }}
          <p v-if="commands[0].status === 'TIMED_OUT'">设备可能已执行，缺少确认不能当成失败。</p>
          <p v-else-if="commands[0].reason">{{ commands[0].reason }}</p>
        </div>
      </div>
      <template #footer>
        <el-button @click="controlVisible = false">关闭</el-button>
        <el-button :loading="loadingCommands" @click="refreshControl">刷新结果</el-button>
      </template>
    </el-dialog>

    <!-- 指令历史抽屉 -->
    <el-drawer v-model="detailVisible" title="指令历史" size="560px">
      <el-alert v-if="commandLoadError" :title="commandLoadError" type="error" :closable="false" />
      <el-table v-loading="loadingCommands" :data="commands" row-key="id" size="small">
        <el-table-column label="目标" width="70">
          <template #default="{ row }">
            <el-tag :type="row.targetPowerStatus === 'ON' ? 'success' : 'info'" size="small">
              {{ row.targetPowerStatus === 'ON' ? '开' : '关' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行结果" width="110">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status]" size="small">{{ STATUS_LABELS[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="150">
          <template #default="{ row }">
            <span v-if="row.reason">{{ row.reason }}</span>
            <span v-else-if="row.status === 'TIMED_OUT'" class="muted">
              设备可能已执行，只是未回报
            </span>
            <span v-else-if="row.resultPowerStatus" class="muted">
              实际状态 {{ row.resultPowerStatus === 'ON' ? '开启' : '关闭' }}
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loadingCommands && !commandLoadError && !commands.length" description="暂无指令记录" />
      <ListPagination :page="commandPage" :size="commandSize" :total="commandTotal" :loading="loadingCommands" @page="changeCommandPage" @size="changeCommandSize"/>
    </el-drawer>

    <!-- 设备详情 -->
    <el-dialog v-model="infoVisible" title="设备详情" width="460px">
      <el-descriptions v-if="infoRow" :column="1" border size="small">
        <el-descriptions-item label="设备编号">{{ infoRow.deviceNo }}</el-descriptions-item>
        <el-descriptions-item label="设备名称">{{ infoRow.deviceName }}</el-descriptions-item>
        <el-descriptions-item label="设备类型">
          {{ TYPE_LABELS[infoRow.deviceType] || infoRow.deviceType }}
        </el-descriptions-item>
        <el-descriptions-item label="接入方式">系统内置模拟</el-descriptions-item>
        <el-descriptions-item label="所属老人">{{ infoRow.elderName || '未分配' }}</el-descriptions-item>
        <el-descriptions-item label="安装位置">{{ infoRow.location || '—' }}</el-descriptions-item>
        <el-descriptions-item label="业务状态">
          {{ infoRow.enabled ? '启用' : '已停用' }}
        </el-descriptions-item>
        <el-descriptions-item label="模拟器">
          {{ infoRow.simulatorRunning ? '运行中' : '已停止' }}
        </el-descriptions-item>
        <el-descriptions-item label="执行场景">
          {{ MODE_LABELS[infoRow.simulationMode] }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="infoVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useDeviceCommand } from '@/composables/useDeviceCommand'
import ListPagination from '@/components/ListPagination.vue'
import { ElMessage } from 'element-plus'
import {
  createDevice,
  getSimulatorStatus,
  listAssignableElders,
  listDeviceCommands,
  listDeviceCommandsPaged,
  listDevices,
  listDevicesPaged,
  setSimulationMode,
  startSimulator,
  stopSimulator,
  updateDevice
} from '@/api/device'

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
const MODE_LABELS = { NORMAL: '正常', FAIL_NEXT: '下次失败', NO_ACK: '不回报' }
const MODE_HINTS = {
  NORMAL: '指令正常执行，设备回报结果。',
  FAIL_NEXT: '下一条指令会失败一次，设备开关不变，之后自动恢复正常。',
  NO_ACK: '下一条指令设备会执行但不回报，最终显示「结果未确认」。'
}

const loading = ref(false)
const simulatorState = ref(null)
const simulatorError = ref('')
const submitting = ref(false)
const { pending, busy, submit, retry, forget } = useDeviceCommand()
let pollTimer
let refreshing = false
const devices = ref([])
const devicePage=ref(1),deviceSize=ref(20),deviceTotal=ref(0),deviceError=ref('')
const elders = ref([])
const current = ref(null)
const commands = ref([])
const commandPage=ref(1),commandSize=ref(20),commandTotal=ref(0)
function changeCommandPage(value){commandPage.value=value;return loadCommands()}
function changeCommandSize(value){commandSize.value=value;return changeCommandPage(1)}
const loadingCommands = ref(false)
const commandLoadError = ref('')

const query = reactive({ deviceType: '', onlineStatus: '' })

const createVisible = ref(false)
const createForm = reactive({
  deviceNo: '',
  deviceName: '',
  deviceType: 'LIGHT',
  elderId: null,
  location: ''
})

const controlVisible = ref(false)
const detailVisible = ref(false)
const infoVisible = ref(false)
const infoRow = ref(null)

async function load(showLoading = true) {
  if (refreshing) return
  refreshing = true
  loading.value = !!showLoading
  try {
    const status = await getSimulatorStatus()
    simulatorState.value = status.data
    simulatorError.value = ''
  } catch {
    simulatorState.value = null
    simulatorError.value = '模拟引擎状态读取失败，请重试'
  }
  try {
    deviceError.value=''
    let res=await listDevicesPaged({...query,pageNum:devicePage.value,pageSize:deviceSize.value})
    const last=Math.max(1,Math.ceil(res.data.total/deviceSize.value))
    if(!res.data.records.length&&devicePage.value>last){devicePage.value=last;res=await listDevicesPaged({...query,pageNum:last,pageSize:deviceSize.value})}
    const list=res.data.records
    deviceTotal.value=res.data.total;devicePage.value=res.data.pageNum
    if (current.value) current.value = list.find(d => d.id === current.value.id) || null
    devices.value = list
  } catch (e) {
    deviceError.value=e?.response?.data?.message||'设备读取失败，请重试'
  } finally {
    loading.value = false
    refreshing = false
  }
}

function resetQuery() {
  devicePage.value=1
  query.deviceType = ''
  query.onlineStatus = ''
  load()
}
function changeDevicePage(value){devicePage.value=value;return load()}
function changeDeviceSize(value){deviceSize.value=value;return changeDevicePage(1)}

function openCreate() {
  createForm.deviceNo = ''
  createForm.deviceName = ''
  createForm.deviceType = 'LIGHT'
  createForm.elderId = null
  createForm.location = ''
  createVisible.value = true
}

async function confirmCreate() {
  if (!createForm.deviceNo.trim() || !createForm.deviceName.trim()) {
    ElMessage.warning('请填写设备编号与名称')
    return
  }
  submitting.value = true
  try {
    await createDevice({
      deviceNo: createForm.deviceNo.trim(),
      deviceName: createForm.deviceName.trim(),
      deviceType: createForm.deviceType,
      elderId: createForm.elderId,
      location: createForm.location.trim()
    })
    ElMessage.success('设备已创建，模拟器需手动启动')
    createVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '创建失败')
  } finally {
    submitting.value = false
  }
}

async function doStartSimulator(row) {
  try {
    await startSimulator(row.id)
    ElMessage.success('模拟器已启动')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '启动失败')
  }
}

async function doStopSimulator(row) {
  try {
    await stopSimulator(row.id)
    ElMessage.warning('模拟器已停止，尚未执行的指令已取消；已经执行但未确认的指令仍需等待到期')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '停止失败')
  }
}

async function toggleEnabled(row) {
  try {
    await updateDevice(row.id, { enabled: !row.enabled })
    ElMessage.success(row.enabled ? '已停用' : '已启用')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

async function openControl(row) {
  commandPage.value=1
  current.value = row
  controlVisible.value = true
  await loadCommands()
}

async function loadCommands() {
  if (!current.value) return
  loadingCommands.value = true
  try {
    const res=await listDeviceCommandsPaged(current.value.id,{pageNum:commandPage.value,pageSize:commandSize.value})
    commands.value=res.data.records;commandTotal.value=res.data.total;commandPage.value=res.data.pageNum
    commandLoadError.value = ''
  } catch (e) {
    commandLoadError.value = '指令记录加载失败，请刷新；不能据此判断设备没有执行'
  } finally {
    loadingCommands.value = false
  }
}

async function onModeChange(mode) {
  if (!current.value) return
  try {
    await setSimulationMode(current.value.id, mode)
    ElMessage.success('场景已切换')
    current.value.simulationMode = mode
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '切换失败')
  }
}

async function onSwitchChange(checked) {
  if (!current.value) return
  await submit(current.value.id, checked ? 'ON' : 'OFF')
  await refreshControl()
}

async function retryCommand() {
  await retry()
  await refreshControl()
}

async function refreshControl() {
  await load(false)
  if (current.value) await loadCommands()
}

async function openDetail(row) {
  commandPage.value=1
  current.value = row
  detailVisible.value = true
  await loadCommands()
}

function openInfo(row) {
  infoRow.value = row
  infoVisible.value = true
}

function formatTime(value) {
  if (!value) return '—'
  return value.replace('T', ' ').slice(0, 19)
}

onMounted(async () => {
  await load()
  try {
    const res = await listAssignableElders()
    elders.value = res.data || []
  } catch (e) {
    elders.value = []
  }
  pollTimer = setInterval(async () => {
    await load(false)
    if (current.value && (controlVisible.value || detailVisible.value)) await loadCommands()
  }, 3000)
})
onUnmounted(() => clearInterval(pollTimer))
</script>

<style scoped>
.simulator-state { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 12px 0 20px; color: var(--el-text-color-secondary); font-size: 13px; }
.page {
  padding: 16px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.page-head h2 {
  margin: 0 0 8px;
}

.sim-notice {
  width: 620px;
  max-width: 100%;
}

.filter {
  margin-bottom: 8px;
}

.dev-name {
  font-weight: 600;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.control-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.control-block {
  margin: 0;
}

.control-form {
  margin-top: 4px;
}

.mode-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  margin: -6px 0 14px 90px;
  line-height: 1.6;
}

.control-tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.7;
  padding: 10px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
}
</style>
