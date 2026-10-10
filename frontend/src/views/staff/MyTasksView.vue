<template>
  <div class="my-tasks" v-loading="loading">
    <el-alert type="info" :closable="false" class="page-note">
      普通服务可主动领取；管理员指定的任务仍需确认接单。开始服务后填写实际结果，再由老人确认完成并评价。
    </el-alert>

    <el-card class="claimable-card" v-loading="poolLoading">
      <template #header><div class="card-header"><span class="card-title">可领取服务</span><el-button link @click="loadPool()">刷新待领取</el-button></div></template>
      <p class="muted">领取前仅展示服务和预约时间，领取成功后可查看本单联系方式。求助与健康异常由社区分派。</p>
      <el-alert v-if="poolError" :title="poolError" type="error" :closable="false"><el-button link @click="loadPool()">重试</el-button></el-alert>
      <el-table v-else-if="poolOrders.length" :data="poolOrders" class="claimable-table" row-key="id">
        <el-table-column prop="orderNo" label="订单号" min-width="190" show-overflow-tooltip/>
        <el-table-column prop="serviceNameSnapshot" label="服务" min-width="140"/>
        <el-table-column label="预约时间" min-width="180"><template #default="{row}">{{formatTime(row)}}</template></el-table-column>
        <el-table-column label="参考价格" width="100"><template #default="{row}">{{row.priceSnapshot}} 元</template></el-table-column>
        <el-table-column label="操作" width="110"><template #default="{row}"><el-button type="primary" size="small" :loading="claimingId===row.id" :disabled="!!claimingId" @click="doClaim(row)">领取服务</el-button></template></el-table-column>
      </el-table>
      <p v-else-if="!poolLoading" class="muted">当前没有待领取服务，已有任务在下方处理。</p>
      <ListPagination v-if="poolTotal>0" :page="poolPage" :size="poolSize" :total="poolTotal" :loading="poolLoading" @page="loadPool" @size="changePoolSize"/>
    </el-card>

    <el-card>
      <template #header>
        <div class="card-header">
          <span class="card-title">我的任务</span>
          <el-button link :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <el-form inline @submit.prevent="load(1)"><el-form-item label="范围"><el-select v-model="query.scope" style="width:130px" @change="query.status='';load(1)"><el-option label="当前任务" value="CURRENT"/><el-option label="历史任务" value="HISTORY"/><el-option label="全部任务" value="ALL"/></el-select></el-form-item><el-form-item label="状态"><el-select v-model="query.status" clearable style="width:140px"><el-option v-for="(label,value) in taskStatusLabels" :key="value" :label="label" :value="value"/></el-select></el-form-item><el-form-item :label="query.dateField==='COMPLETION'?'完成日期':'预约日期'"><el-date-picker v-model="query.fromDate" type="date" value-format="YYYY-MM-DD" style="width:145px" placeholder="开始日期"/><el-date-picker v-model="query.toDate" type="date" value-format="YYYY-MM-DD" style="width:145px" placeholder="结束日期"/></el-form-item><el-form-item v-if="query.excludeEnded"><el-tag closable @close="query.excludeEnded=false;load(1)">排除取消与终止</el-tag></el-form-item><el-button native-type="submit" type="primary">查询</el-button><el-button @click="resetTaskFilter">重置</el-button></el-form>
      <h4 v-if="grouped.ASSIGNED.length" class="section-title">本页待接单任务</h4>
      <el-table class="current-tasks-table" :data="grouped.ASSIGNED" v-if="grouped.ASSIGNED.length" row-key="id">
        <el-table-column prop="orderNo" label="订单号" width="185" show-overflow-tooltip />
        <el-table-column prop="serviceNameSnapshot" label="服务" min-width="100" />
        <el-table-column label="时段" min-width="160">
          <template #default="{ row }">{{ formatTime(row) }}</template>
        </el-table-column>
        <el-table-column prop="contactName" label="联系人" width="80" />
        <el-table-column prop="contactPhone" label="电话" width="115" />
        <el-table-column label="状态" width="90">
          <template #default><el-tag type="warning">待接单</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="info" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="openAccept(row)">接单</el-button>
            <el-button link type="danger" @click="openReject(row)">拒接</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else-if="!loading && !loadError && !total" description="暂无服务任务" />
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon>
        <el-button link @click="load()">重试</el-button>
      </el-alert>

      <template v-if="executingOrders.length">
        <h4 class="section-title">本页服务进行中</h4>
        <el-table class="current-tasks-table" :data="executingOrders" row-key="id">
          <el-table-column prop="orderNo" label="订单号" width="185" show-overflow-tooltip />
          <el-table-column prop="serviceNameSnapshot" label="服务" min-width="100" />
          <el-table-column label="时段" min-width="160">
            <template #default="{ row }">{{ formatTime(row) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'IN_SERVICE' ? 'warning' : 'primary'">
                {{ row.status === 'IN_SERVICE' ? '服务中' : '待确认' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="{ row }">
              <el-button link type="info" @click="openDetail(row)">详情</el-button>
              <el-button
                v-if="row.status === 'IN_SERVICE'"
                link
                type="primary"
                @click="openSubmit(row)"
              >提交结果</el-button>
              <span v-else class="muted">等待老人确认</span>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="grouped.ACCEPTED.length">
        <h4 class="section-title">本页已接单，待开工</h4>
        <el-table class="current-tasks-table" :data="grouped.ACCEPTED" row-key="id">
          <el-table-column prop="orderNo" label="订单号" width="185" show-overflow-tooltip />
          <el-table-column prop="serviceNameSnapshot" label="服务" min-width="100" />
          <el-table-column label="时段" min-width="160">
            <template #default="{ row }">{{ formatTime(row) }}</template>
          </el-table-column>
          <el-table-column label="接单时间" min-width="150">
            <template #default="{ row }">{{ formatDateTime(row.acceptedAt) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default><el-tag type="primary">待服务</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" min-width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="info" @click="openDetail(row)">详情</el-button>
              <el-button
                v-if="row.status === 'ACCEPTED'"
                link
                type="primary"
                :disabled="!canStart(row) || submitting"
                @click="doStart(row)"
              >{{canStart(row)?'开始服务':'预约时间未到'}}</el-button>
              <div v-if="!canStart(row)" class="muted">{{formatDateTime(row.slotStartSnapshot)}} 起可开工</div>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <!-- 服务端分页：翻页换不掉归属（staffId 由会话决定） -->
      <el-table v-if="endedOrders.length" class="current-tasks-table" :data="endedOrders"><el-table-column prop="orderNo" label="订单号" min-width="190" show-overflow-tooltip/><el-table-column prop="serviceNameSnapshot" label="服务" min-width="140"/><el-table-column label="预约时间" min-width="180"><template #default="{row}">{{formatTime(row)}}</template></el-table-column><el-table-column label="状态" width="100"><template #default="{row}">{{taskStatusLabels[row.status]}}</template></el-table-column><el-table-column label="操作" width="90"><template #default="{row}"><el-button link @click="openDetail(row)">详情</el-button></template></el-table-column></el-table>
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

      <!-- 共用订单详情 -->
      <OrderDetailDrawer
        v-model="detailVisible"
        :order-id="detailOrderId"
        :available-actions="staffActions"
        @detail-loaded="onDetailLoaded"
        @changed="load"
      >
        <template #actions="{ actions, trigger }">
          <el-button
            v-for="a in actions"
            :key="a.key"
            :type="a.type || 'primary'"
            @click="runDetailAction(a, trigger)"
          >{{ a.label }}</el-button>
        </template>
      </OrderDetailDrawer>

      <el-alert v-if="reviewError" :title="reviewError" type="warning" :closable="false"><el-button link @click="loadReviews()">重试</el-button></el-alert>
      <template v-else-if="reviews.length">
        <h4 class="section-title">最近收到的评价 · 最多5条 <router-link to="/staff/reviews">查看全部{{reviewTotal}}条</router-link></h4>
        <el-table :data="reviews" row-key="id">
          <el-table-column label="评分" width="150">
            <template #default="{ row }">
              <el-rate :model-value="row.rating" disabled show-score
                       score-template="{value} 分" />
            </template>
          </el-table-column>
          <el-table-column prop="content" label="评价内容" min-width="180">
            <template #default="{ row }">{{ row.content || '（未填写文字）' }}</template>
          </el-table-column>
          <el-table-column label="评价时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </template>

      <!-- 参与历史只展示脱敏摘要，转派后保留本人参与记录，不提供当前详情或操作入口。 -->
      <h4 class="section-title">我参与过的服务</h4>
      <el-alert
        v-if="participationError"
        type="error"
        :closable="false"
        class="participation-error"
        :title="participationError"
      >
        <el-button link type="primary" @click="loadParticipations">重试</el-button>
      </el-alert>
      <el-table
        v-else-if="participations.length"
        :data="participations"
        row-key="orderId"
        size="small"
      >
        <el-table-column prop="orderNo" label="订单号" width="185" show-overflow-tooltip />
        <el-table-column prop="serviceNameSnapshot" label="服务" min-width="100" />
        <el-table-column label="时段" min-width="160">
          <template #default="{ row }">{{ formatTime(row) }}</template>
        </el-table-column>
        <el-table-column prop="elderName" label="老人" width="80" />
        <el-table-column label="我做过" min-width="120"><template #default="{row}">{{formatOrderActions(row.myActions)}}</template></el-table-column>
        <el-table-column label="当前状态" width="95">
          <template #default="{ row }">
            <el-tag :type="row.finalStatus === 'COMPLETED' ? 'success' : 'info'" size="small">
              {{ statusLabel(row.finalStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后动作时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.lastActionAt) }}</template>
        </el-table-column>
      </el-table>
      <el-empty
        v-else-if="!participationLoading"
        description="暂无参与记录"
        description-size="small"
      />
      <p class="readonly-tip participation-tip">
        这里只显示你本人参与过的脱敏摘要：不含当前电话与地址，也不再提供操作入口。
      </p>
      <ListPagination :page="historyPage" :size="historySize" :total="historyTotal" :loading="participationLoading" @page="loadParticipations" @size="changeHistorySize"/>

    </el-card>

    <!-- 提交服务结果 -->
    <el-dialog v-model="submitVisible" title="提交服务结果" width="440px">
      <p class="dialog-tip">请如实填写本次服务的内容与结果，老人确认后会归档。</p>
      <el-input
        v-model="submitText"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        placeholder="例如：已完成客厅与卫生间保洁，垃圾已带走"
      />
      <template #footer>
        <el-button @click="submitVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 接单/拒接共用一个对话框 -->
    <el-dialog v-model="actionVisible" :title="actionTitle" width="420px">
      <p class="dialog-tip">{{ actionTip }}</p>
      <el-input
        v-model="actionReason"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        :placeholder="action === 'reject' ? '请填写拒接原因（必填）' : '可填写说明（选填）'"
      />
      <template #footer>
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button
          :type="action === 'reject' ? 'danger' : 'primary'"
          :loading="submitting"
          @click="confirmAction"
        >{{ action === 'reject' ? '确认拒接' : '确认接单' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import request from '@/utils/request'
import ListPagination from '@/components/ListPagination.vue'
import { ElMessage } from 'element-plus'
import {
  acceptOrder,
  listMyParticipations,
  listMyTasksPaged,
  rejectOrder,
  listClaimableOrders,
  claimOrder,
  startService,
  submitResult as submitResultApi
} from '@/api/order'
import OrderDetailDrawer from '@/views/shared/OrderDetailDrawer.vue'
import { formatOrderActions } from '@/utils/orderLabels'

const loading = ref(false)
let taskLoadId = 0
const loadError = ref('')
const submitting = ref(false)
const now = ref(Date.now())
let timeTimer
const poolOrders = ref([]), poolPage = ref(1), poolSize = ref(20), poolTotal = ref(0)
const poolLoading = ref(false), poolError = ref(''), claimingId = ref(null)
const tasks = ref([])
const reviews = ref([])
const reviewTotal = ref(0)
const reviewError = ref('')
const total = ref(0)
const route=useRoute()
const taskStatusLabels={ASSIGNED:'待接单',ACCEPTED:'待服务',IN_SERVICE:'服务中',PENDING_CONFIRMATION:'待确认',COMPLETED:'已完成',CANCELLED:'已取消',TERMINATED:'已终止'}
const query = ref({ pageNum: 1, pageSize: 20,scope:route.query.scope||(route.query.status==='COMPLETED'?'HISTORY':'CURRENT'),dateField:route.query.dateField||'APPOINTMENT',status:route.query.status||'',fromDate:route.query.fromDate||'',toDate:route.query.toDate||'',excludeEnded:route.query.excludeEnded==='true' })
const endedOrders=computed(()=>tasks.value.filter(t=>['COMPLETED','CANCELLED','TERMINATED'].includes(t.status)))
function resetTaskFilter(){Object.assign(query.value,{scope:'CURRENT',dateField:'APPOINTMENT',status:'',fromDate:'',toDate:'',excludeEnded:false});return load(1)}

// 参与历史：与「我的任务」分开的次级区块。
// 失败与真实空态分别展示——把加载失败显示成「暂无记录」会让人以为真没有。
const participations = ref([])
const participationLoading = ref(false)
const participationError = ref('')
const historyPage=ref(1),historySize=ref(20),historyTotal=ref(0)
function changeHistorySize(value){historySize.value=value;return loadParticipations(1)}

// 共用详情抽屉
const detailVisible = ref(false)
const detailOrderId = ref(null)
// 抽屉加载完详情后透过来的数据；动作清单按它的状态算
const detailCache = ref(null)

const actionVisible = ref(false)
const submitVisible = ref(false)
const submitText = ref('')
const submitTarget = ref(null)
const action = ref('accept')
const currentOrder = ref(null)
const actionReason = ref('')

const grouped = computed(() => {
  const result = { ASSIGNED: [], ACCEPTED: [], IN_SERVICE: [], PENDING_CONFIRMATION: [] }
  tasks.value.forEach((t) => {
    if (result[t.status]) result[t.status].push(t)
  })
  return result
})

const executingOrders = computed(() => [
  ...grouped.value.IN_SERVICE,
  ...grouped.value.PENDING_CONFIRMATION
])

const actionTitle = computed(() => (action.value === 'reject' ? '拒接任务' : '接受任务'))
const actionTip = computed(() => {
  const o = currentOrder.value
  if (!o) return ''
  const base = `${o.serviceNameSnapshot} · ${formatTime(o)} · ${o.contactName}`
  return action.value === 'reject'
    ? `拒接后订单回到待分派，由社区重新安排人员。${base}`
    : `确认后将进入服务流程。${base}`
})

// 三个数据源各自独立加载：任一失败不该把另外两个也拖成空。
onMounted(() => {
  load()
  loadParticipations()
  loadPool()
  timeTimer = setInterval(() => { now.value = Date.now() }, 1000)
})
onUnmounted(() => clearInterval(timeTimer))

function canStart(order) {
  return !!order.slotStartSnapshot && new Date(order.slotStartSnapshot).getTime() <= now.value
}

async function loadPool(next = poolPage.value, recover = true) {
  poolPage.value = Number.isInteger(next) && next > 0 ? next : poolPage.value
  poolLoading.value = true; poolError.value = ''
  try {
    const res = await listClaimableOrders({pageNum:poolPage.value,pageSize:poolSize.value})
    const last = Math.max(1, Math.ceil(res.data.total / poolSize.value))
    if (!res.data.records.length && poolPage.value > last && recover) return await loadPool(last, false)
    poolOrders.value = res.data.records; poolTotal.value = res.data.total
  } catch (e) { poolError.value = e?.message || '待领取服务读取失败，请重试' }
  finally { poolLoading.value = false }
}

function changePoolSize(size) { poolSize.value = size; return loadPool(1) }

async function doClaim(row) {
  if (claimingId.value) return
  claimingId.value = row.id
  try {
    await claimOrder(row.id, row.version)
    ElMessage.success('已领取并接单，可在我的任务中查看详情')
    await load(1)
  } catch (e) { ElMessage.error(e?.response?.data?.message || e?.message || '领取失败，请刷新') }
  finally { claimingId.value = null; await loadPool() }
}

async function loadReviews(requestId = taskLoadId) {
  reviewError.value = ''
  try {
    const res = await request.get('/service-orders/reviews/received/paged', {params:{pageNum:1,pageSize:5}})
    if (requestId !== taskLoadId) return
    reviews.value = res.data.records
    reviewTotal.value = res.data.ratedCount
  } catch (e) {
    if (requestId === taskLoadId) reviewError.value = '近期评价读取失败，请重试或进入收到的评价'
  }
}

/**
 * 读取本人的只读参与历史，失败时显示错误与重试入口，空结果单独显示空态。
 */
async function loadParticipations(next=historyPage.value,recover=true) {
  participationLoading.value = true
  participationError.value = ''
  try {
    const res=await request.get('/service-orders/my-participations/paged',{params:{pageNum:Number.isSafeInteger(next)&&next>0?next:historyPage.value,pageSize:historySize.value}})
    participations.value=res.data.records;historyPage.value=res.data.pageNum;historyTotal.value=res.data.total
    const last=Math.max(1,Math.ceil(historyTotal.value/historySize.value));if(recover&&!participations.value.length&&historyPage.value>last)return await loadParticipations(last,false)
  } catch (e) {
    participations.value = []
    participationError.value = e?.response?.data?.message || '参与历史加载失败'
  } finally {
    participationLoading.value = false
  }
}

/** 状态文案。参与历史显示的是**当前**状态，不是本人最后动作时的状态 */
function statusLabel(status) {
  return {
    PENDING_ASSIGNMENT: '待分派',
    ASSIGNED: '待接单',
    ACCEPTED: '待开工',
    IN_SERVICE: '服务中',
    PENDING_CONFIRMATION: '待确认',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
    TERMINATED: '已终止'
  }[status] || status || '—'
}

/**
 * 加载分派给我的任务（服务端分页）。
 *
 * <p>翻页只改 pageNum，**不带任何归属参数**——staffId 由会话决定。
 */
async function load(pageNum, recoverEmpty = true) {
  if (Number.isSafeInteger(pageNum) && pageNum > 0) {
    query.value.pageNum = pageNum
  }
  const requestId = ++taskLoadId
  const params = { ...query.value }
  loading.value = true
  loadError.value = ''
  try {
    const res = await listMyTasksPaged({
      scope:params.scope,dateField:params.dateField,status:params.status||undefined,fromDate:params.fromDate||undefined,toDate:params.toDate||undefined,excludeEnded:params.excludeEnded,
      pageNum: params.pageNum,
      pageSize: params.pageSize
    })
    if (requestId !== taskLoadId) return
    tasks.value = res.data?.records || []
    total.value = res.data?.total || 0
    const lastPage = Math.max(1, Math.ceil(total.value / params.pageSize))
    if (recoverEmpty && !tasks.value.length && params.pageNum > lastPage) {
      await load(lastPage, false)
      return
    }
    await loadReviews(requestId)
  } catch (e) {
    if (requestId === taskLoadId) loadError.value = '任务读取失败，请重试'
  } finally {
    if (requestId === taskLoadId) loading.value = false
  }
}

function changePageSize(size) {
  if (![10, 20, 50, 100].includes(size)) return
  query.value.pageSize = size
  return load(1)
}

function openDetail(row) {
  detailOrderId.value = String(row.id)
  detailCache.value = null
  detailVisible.value = true
}

function onDetailLoaded(detail) {
  detailCache.value = detail
}

/**
 * 工作人员在详情里能做什么：接单、拒接、开工、提交结果。
 *
 * <p>只列他本人被分派到的订单上的合法动作；分派与转派属于管理员。
 */
const staffActions = computed(() => {
  const d = detailCache.value
  if (!d) {
    return []
  }
  const list = []
  if (d.status === 'ASSIGNED') {
    list.push({ key: 'accept', label: '接单', type: 'primary' })
    list.push({ key: 'reject', label: '拒接', type: 'danger' })
  }
  if (d.status === 'ACCEPTED') {
    if (canStart(d)) list.push({ key: 'start', label: '开始服务', type: 'primary' })
  }
  if (d.status === 'IN_SERVICE') {
    list.push({ key: 'submit', label: '提交服务结果', type: 'primary' })
  }
  return list
})

async function runDetailAction(action, trigger) {
  // 详情入口与列表共用表单，不能替人员编造服务结果或拒接原因。
  if (action.key === 'submit') { openSubmit(detailCache.value); detailVisible.value = false; return }
  if (action.key === 'reject') { openReject(detailCache.value); detailVisible.value = false; return }
  const handlers = {
    accept: async (d) => {
      await acceptOrder(d.id, '工作人员在详情中接单', d.version)
      ElMessage.success('已接单')
    },
    start: async (d) => {
      // 带上详情里读到的版本：不带等于绕过乐观锁
      await startService(d.id, d.version)
      ElMessage.success('已开始服务')
    }
  }
  await trigger(action.key, handlers[action.key])
}

function openAccept(row) {
  action.value = 'accept'
  currentOrder.value = row
  actionReason.value = ''
  actionVisible.value = true
}

function openReject(row) {
  action.value = 'reject'
  currentOrder.value = row
  actionReason.value = ''
  actionVisible.value = true
}

async function confirmAction() {
  if (action.value === 'reject' && !actionReason.value.trim()) {
    ElMessage.warning('请填写拒接原因')
    return
  }
  submitting.value = true
  try {
    if (action.value === 'reject') {
      await rejectOrder(currentOrder.value.id, actionReason.value.trim(), currentOrder.value.version)
      ElMessage.success('已拒接，订单回到待分派')
    } else {
      await acceptOrder(currentOrder.value.id, actionReason.value.trim() || '已确认接单', currentOrder.value.version)
      ElMessage.success('接单成功')
    }
    actionVisible.value = false
    await load()
  } catch (e) {
    // 状态可能已被他人改变，提示刷新而不是显示成功
    ElMessage.error(e?.response?.data?.message || e?.message || '操作失败，请刷新后重试')
  } finally {
    submitting.value = false
  }
}

async function doStart(row) {
  submitting.value = true
  try {
    await startService(row.id, row.version)
    ElMessage.success('已开工')
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '开工失败，请刷新后重试')
  } finally {
    submitting.value = false
  }
}

function openSubmit(row) {
  submitTarget.value = row
  submitText.value = ''
  submitVisible.value = true
}

async function confirmSubmit() {
  if (!submitText.value.trim()) {
    ElMessage.warning('请填写服务结果')
    return
  }
  submitting.value = true
  try {
    await submitResultApi(submitTarget.value.id, submitText.value.trim(),
      submitTarget.value.version)
    ElMessage.success('已提交，等待老人确认')
    submitVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '提交失败，请刷新后重试')
  } finally {
    submitting.value = false
  }
}

function formatTime(row) {
  const fmt = (v) => (v ? v.replace('T', ' ').slice(5, 16) : '')
  return `${fmt(row.slotStartSnapshot)} — ${fmt(row.slotEndSnapshot).slice(-5)}`
}

function formatDateTime(v) {
  return v ? v.replace('T', ' ').slice(0, 16) : '—'
}
</script>

<style scoped>
.claimable-card { margin-bottom: 18px; }
.page-note {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 15px;
  font-weight: 500;
}

.section-title {
  font-size: 14px;
  font-weight: 500;
  margin: 20px 0 10px;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.dialog-tip {
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 12px;
  line-height: 1.6;
}

/* 参与历史：次级区块的间距与错误态 */
.participation-error {
  margin-bottom: 8px;
}

.participation-tip {
  margin-top: 6px;
}
</style>
