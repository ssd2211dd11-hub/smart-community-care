<template>
  <div class="admin-orders" v-loading="loading">
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="card-title">全部订单</span>
          <el-button link :loading="loading" @click="load(1)">刷新</el-button>
        </div>
      </template>

      <el-form inline class="filter-bar">
        <el-form-item :label="query.dateField==='COMPLETION'?'完成日期':'预约日期'"><el-date-picker v-model="query.fromDate" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" style="width:150px"/><span>至</span><el-date-picker v-model="query.toDate" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" style="width:150px"/></el-form-item>
        <el-form-item v-if="query.excludeEnded"><el-tag closable @close="query.excludeEnded=false;load(1)">排除取消与终止</el-tag></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 160px">
            <el-option v-for="(label, value) in STATUS_LABELS" :key="value"
                       :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon>
        <el-button link @click="load()">重试</el-button>
      </el-alert>
      <el-table :data="orders" v-if="orders.length" row-key="id">
        <el-table-column prop="orderNo" label="订单号" width="190" show-overflow-tooltip />
        <el-table-column prop="serviceNameSnapshot" label="服务" min-width="110" />
        <el-table-column label="时段" min-width="170">
          <template #default="{ row }">{{ formatTime(row) }}</template>
        </el-table-column>
        <el-table-column label="金额" width="90">
          <template #default="{ row }">{{ row.priceSnapshot }} 元</template>
        </el-table-column>
        <el-table-column prop="contactName" label="联系人" width="90" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else-if="!loading && !loadError" description="没有符合条件的订单" />

      <el-pagination
        class="pager"
        layout="total, sizes, prev, pager, next, jumper"
        :page-sizes="[10, 20, 50, 100]"
        :disabled="loading"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNum"
        @current-change="load"
        @size-change="changePageSize"
      />
    </el-card>

    <!-- 订单详情 -->
    <el-drawer v-model="detailVisible" title="订单详情" size="520px">
      <div v-if="detail" class="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="服务">{{ detail.serviceNameSnapshot }}</el-descriptions-item>
          <el-descriptions-item label="参考金额">
            {{ detail.priceSnapshot }} 元
            <span class="hint">（参考价格，不代表收入）</span>
          </el-descriptions-item>
          <el-descriptions-item label="预约时段">{{ formatTime(detail) }}</el-descriptions-item>
          <el-descriptions-item label="联系人">
            {{ detail.contactName }} · {{ detail.contactPhone }}
          </el-descriptions-item>
          <el-descriptions-item label="地址">{{ detail.addressSnapshot || '—' }}</el-descriptions-item>
          <!-- 当前责任人取订单现值；未分派时明确显示未分派。 -->
          <el-descriptions-item label="当前责任人">
            <span v-if="detail.staffName">{{ detail.staffName }}</span>
            <el-tag v-else type="info" size="small">未分派</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="当前状态">
            <el-tag :type="statusType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="服务结果">
            {{ detail.serviceResult || '尚未提交' }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="section-title">可执行操作</h4>
        <div v-if="detailActions.length" class="action-row">
          <el-button
            v-for="a in detailActions"
            :key="a.key"
            :type="a.type"
            :loading="submitting"
            @click="startAction(a.key)"
          >{{ a.label }}</el-button>
        </div>
        <p v-else class="no-action">
          当前状态「{{ statusLabel(detail.status) }}」没有可执行的操作。
        </p>

        <h4 class="section-title">处理时间线</h4>
        <el-timeline v-if="logs.length">
          <el-timeline-item
            v-for="log in logs"
            :key="log.id"
            :timestamp="formatDateTime(log.createdAt)"
            placement="top"
          >
            <div class="log-action">{{ orderActionLabel(log.action) }}：{{ orderStatusLabel(log.fromStatus) }} → {{ orderStatusLabel(log.toStatus) }}</div>
            <div v-if="log.reason" class="log-reason">{{ log.reason }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无处理记录" description-size="small" />
      </div>
    </el-drawer>
    <!-- 分派/转派弹窗 -->
    <el-dialog v-model="assignVisible" :title="assignTitle" width="420px">
      <el-form label-width="90px">
        <el-form-item :label="assignKey === 'transfer' ? '新服务人员' : '服务人员'">
          <el-select v-model="assignForm.staffId" style="width: 100%" placeholder="请选择">
            <el-option v-for="s in staffOptions" :key="s.id"
                       :label="s.realName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="assignKey === 'transfer' ? '转派原因' : '说明'">
          <el-input v-model="assignForm.reason" type="textarea" :rows="3"
                    maxlength="200" show-word-limit
                    :placeholder="assignKey === 'transfer' ? '必填' : '选填'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmAssign">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="confirmVisible" title="代确认服务完成" width="440px"
               :close-on-click-modal="false" :close-on-press-escape="!submitting" :show-close="!submitting">
      <p class="dialog-tip">请先核实服务已完成并得到老人认可。代确认原因会保留在订单处理记录中。</p>
      <el-input v-model="confirmReason" type="textarea" :rows="3" maxlength="500" show-word-limit
                :disabled="submitting" placeholder="请填写核实情况及代确认原因" />
      <template #footer>
        <el-button :disabled="submitting" @click="confirmVisible = false">返回</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmCompleteAsAdmin">确认完成</el-button>
      </template>
    </el-dialog>

    <!-- 异常终止：原因必填 -->
    <el-dialog v-model="terminateVisible" title="异常终止订单" width="440px">
      <p class="dialog-tip">
        终止表示服务未能完成，<b>不会释放时段名额</b>（服务已经发生）。
        这是终态，终止后不能再确认完成，也不能评价。
      </p>
      <el-input
        v-model="terminateReason"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        placeholder="请说明终止原因，例如老人临时住院、服务无法继续"
      />
      <template #footer>
        <el-button @click="terminateVisible = false">返回</el-button>
        <el-button type="danger" :loading="submitting" @click="confirmTerminate">确认终止</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  assignOrder,
  confirmAsAdmin,
  getOrderView,
  listAllOrders,
  terminateOrder,
  transferOrder
} from '@/api/order'
import { listAssignableStaff } from '@/api/order'
import request from '@/utils/request'
import { orderActionLabel, orderStatusLabel } from '@/utils/orderLabels'

/** 与后端 OrderStatus 枚举一致 */
const STATUS_LABELS = {
  PENDING_ASSIGNMENT: '待领取 / 待分派',
  ASSIGNED: '待接单',
  ACCEPTED: '待服务',
  IN_SERVICE: '服务中',
  PENDING_CONFIRMATION: '待确认',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  TERMINATED: '已终止'
}

const STATUS_TYPES = {
  PENDING_ASSIGNMENT: 'info',
  ASSIGNED: 'warning',
  ACCEPTED: 'warning',
  IN_SERVICE: 'primary',
  PENDING_CONFIRMATION: 'primary',
  COMPLETED: 'success',
  CANCELLED: 'info',
  TERMINATED: 'danger'
}

const loading = ref(false)
let orderLoadId = 0
const loadError = ref('')
const orders = ref([])
const total = ref(0)
const route=useRoute()
const query = ref({ pageNum: 1, pageSize: 20, dateField:route.query.dateField||'APPOINTMENT',status: typeof route.query.status==='string'?route.query.status:'',fromDate:route.query.fromDate||'',toDate:route.query.toDate||'',excludeEnded:route.query.excludeEnded==='true' })

const detailVisible = ref(false)
const detail = ref(null)
const logs = ref([])

const submitting = ref(false)
const staffOptions = ref([])

const assignVisible = ref(false)
const assignKey = ref('assign')
const assignForm = ref({ staffId: null, reason: '' })

const terminateVisible = ref(false)
const terminateReason = ref('')
const confirmVisible = ref(false)
const confirmReason = ref('')
const confirmTarget = ref(null)

const assignTitle = computed(() => (assignKey.value === 'transfer' ? '转派订单' : '分派订单'))

/**
 * 当前状态下可执行的管理员动作。
 *
 * <p>与后端状态机保持一致：只有待分派能分派，待接单/待服务能转派。
 * 这里只控制<b>显示</b>，后端仍会独立校验——前端隐藏不等于后端放行。
 */
const detailActions = computed(() => {
  if (!detail.value) return []
  const s = detail.value.status
  if (s === 'PENDING_ASSIGNMENT') {
    return [{ key: 'assign', label: '分派服务人员', type: 'primary' }]
  }
  if (s === 'ASSIGNED' || s === 'ACCEPTED') {
    return [{ key: 'transfer', label: '转派他人', type: 'warning' }]
  }
  if (s === 'PENDING_CONFIRMATION') {
    return [
      { key: 'confirmAdmin', label: '代确认完成', type: 'primary' },
      { key: 'terminate', label: '异常终止', type: 'danger' }
    ]
  }
  if (s === 'IN_SERVICE') {
    // 服务已开始或待确认，只能由管理员核实后终止——不能走取消释放名额
    return [{ key: 'terminate', label: '异常终止', type: 'danger' }]
  }
  return []
})

onMounted(() => load(1))

async function load(pageNum, recoverEmpty = true) {
  if (Number.isSafeInteger(pageNum) && pageNum > 0) query.value.pageNum = pageNum
  const requestId = ++orderLoadId
  const requested = { ...query.value }
  loading.value = true
  loadError.value = ''
  try {
    const params = { pageNum: requested.pageNum, pageSize: requested.pageSize,dateField:requested.dateField,fromDate:requested.fromDate||undefined,toDate:requested.toDate||undefined,excludeEnded:requested.excludeEnded }
    if (requested.status) params.status = requested.status
    const res = await listAllOrders(params)
    if (requestId !== orderLoadId) return
    orders.value = res.data?.records || []
    total.value = res.data?.total || 0
    const lastPage = Math.max(1, Math.ceil(total.value / requested.pageSize))
    if (recoverEmpty && !orders.value.length && requested.pageNum > lastPage) {
      await load(lastPage, false)
    }
  } catch (e) {
    if (requestId === orderLoadId) loadError.value = '订单读取失败，请重试'
  } finally {
    if (requestId === orderLoadId) loading.value = false
  }
}

function changePageSize(size) {
  if (![10, 20, 50, 100].includes(size)) return
  query.value.pageSize = size
  return load(1)
}

function resetFilter() {
  query.value.status = ''
  query.value.fromDate='';query.value.toDate='';query.value.excludeEnded=false;query.value.dateField='APPOINTMENT'
  load(1)
}

async function startAction(key) {
  if (key === 'confirmAdmin') {
    if (detail.value?.status !== 'PENDING_CONFIRMATION') return
    confirmTarget.value = { id: detail.value.id, version: detail.value.version }
    confirmReason.value = ''
    confirmVisible.value = true
    return
  }
  if (key === 'terminate') {
    terminateReason.value = ''
    terminateVisible.value = true
    return
  }
  assignKey.value = key
  assignForm.value = { staffId: null, reason: '' }
  if (staffOptions.value.length === 0) {
    try {
      const res = await listAssignableStaff()
      staffOptions.value = res.data || []
    } catch (e) {
      ElMessage.error('人员列表加载失败')
      return
    }
  }
  assignVisible.value = true
}

async function confirmAssign() {
  if (!assignForm.value.staffId) {
    ElMessage.warning('请选择服务人员')
    return
  }
  if (assignKey.value === 'transfer' && !assignForm.value.reason.trim()) {
    ElMessage.warning('转派必须填写原因')
    return
  }
  submitting.value = true
  try {
    if (assignKey.value === 'transfer') {
      await transferOrder(detail.value.id, assignForm.value.staffId, assignForm.value.reason.trim(), detail.value.version)
      ElMessage.success('已转派，新人员需重新接单')
    } else {
      await assignOrder(detail.value.id, assignForm.value.staffId, assignForm.value.reason.trim() || '管理员分派', detail.value.version)
      ElMessage.success('分派成功')
    }
    assignVisible.value = false
    await openDetail(detail.value)
    await load(query.value.pageNum)
  } catch (e) {
    ElMessage.error(e?.message || '操作失败，请刷新后重试')
  } finally {
    submitting.value = false
  }
}

async function confirmCompleteAsAdmin() {
  if (submitting.value || !confirmTarget.value) return
  const reason = confirmReason.value.trim()
  if (!reason) {
    ElMessage.warning('请填写代确认原因')
    return
  }
  const target = confirmTarget.value
  submitting.value = true
  try {
    await confirmAsAdmin(target.id, reason, target.version)
    confirmVisible.value = false
    ElMessage.success('已代确认服务完成')
    await openDetail(target)
    await load(query.value.pageNum)
  } catch (e) {
    ElMessage.error(e?.message || '代确认失败，请刷新订单后重试')
  } finally {
    submitting.value = false
  }
}

async function confirmTerminate() {
  if (!terminateReason.value.trim()) {
    ElMessage.warning('请填写终止原因')
    return
  }
  submitting.value = true
  try {
    await terminateOrder(detail.value.id, terminateReason.value.trim(), detail.value.version)
    ElMessage.success('订单已终止，名额不释放')
    terminateVisible.value = false
    await openDetail(detail.value)
    await load(query.value.pageNum)
  } catch (e) {
    ElMessage.error(e?.message || '终止失败，请刷新后重试')
  } finally {
    submitting.value = false
  }
}

async function openDetail(row) {
  try {
    const [orderRes, logRes] = await Promise.all([
      // 用 /view 而不是 /{id}：后者返回数据库实体，
      // 既没有 staffName（拿不到「当前责任人」），
      // 也会把 requestHash 等内部字段带到页面上。
      getOrderView(row.id),
      request.get(`/service-orders/${row.id}/logs`)
    ])
    detail.value = orderRes.data
    logs.value = logRes.data || []
    detailVisible.value = true
  } catch (e) {
    ElMessage.error('订单详情加载失败')
  }
}

function statusLabel(status) {
  return STATUS_LABELS[status] || status
}

function statusType(status) {
  return STATUS_TYPES[status] || 'info'
}

function formatTime(row) {
  const fmt = (v) => (v ? v.replace('T', ' ').slice(5, 16) : '')
  return `${fmt(row.slotStartSnapshot)} — ${fmt(row.slotEndSnapshot).slice(-5)}`
}

function formatDateTime(v) {
  return v ? v.replace('T', ' ').slice(0, 19) : ''
}
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 15px;
  font-weight: 500;
}

.filter-bar {
  margin-bottom: 8px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.section-title {
  font-size: 14px;
  font-weight: 500;
  margin: 18px 0 12px;
}

.action-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.no-action {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin: 0;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.log-action {
  font-size: 13px;
}

.log-reason {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
</style>
