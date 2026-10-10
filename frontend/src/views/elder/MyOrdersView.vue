<template>
  <div class="my-orders" v-loading="loading">
    <p class="muted">服务人员提交实际结果后，由您确认完成；完成后可给本单服务人员评分并留言，每单一次。</p>
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="card-title">我的订单</span>
          <el-button link :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <el-alert type="info" :closable="false" class="page-note">
        取消预约需在服务时段开始前操作，且每个订单只能取消一次。
      </el-alert>

      <el-alert v-if="reviewReadError" :title="reviewReadError" type="warning" :closable="false" show-icon />

      <el-table :data="orders" v-if="orders.length" row-key="id">
        <el-table-column prop="orderNo" label="订单号" width="200" show-overflow-tooltip />
        <el-table-column prop="serviceNameSnapshot" label="服务" min-width="120" />
        <el-table-column label="时段" min-width="180">
          <template #default="{ row }">{{ formatTime(row) }}</template>
        </el-table-column>
        <el-table-column label="金额" width="100">
          <template #default="{ row }">{{ row.priceSnapshot }} 元</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="info" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'PENDING_CONFIRMATION'"
              link
              type="primary"
              @click="confirmComplete(row)"
            >确认完成</el-button>
            <el-button
              v-if="row.status === 'PENDING_CONFIRMATION'"
              link
              type="warning"
              @click="openReturn(row)"
            >未完成</el-button>
            <el-button
              v-if="row.status === 'COMPLETED' && reviewedIds.indexOf(String(row.id)) === -1"
              link
              type="primary"
              :disabled="!!reviewReadError"
              @click="openReview(row)"
            >评价</el-button>
            <el-button
              v-if="row.status === 'COMPLETED' && reviewedIds.indexOf(String(row.id)) !== -1"
              link
              disabled
            >已评价</el-button>
            <el-button
              v-if="cancellable(row)"
              link
              type="danger"
              @click="openCancel(row)"
            >取消</el-button>
            <span
              v-if="!cancellable(row)
                && row.status !== 'PENDING_CONFIRMATION'
                && row.status !== 'COMPLETED'"
              class="muted"
            >—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else-if="!loading && !loadError && !total" description="还没有预约记录" />
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon>
        <el-button link @click="load()">重试</el-button>
      </el-alert>

      <!-- 服务端分页：翻页换不掉归属（elderId 由会话决定） -->
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
        :available-actions="elderActions"
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
    </el-card>

    <!-- 取消需填原因，不允许静默取消 -->
    <el-dialog v-model="cancelVisible" title="取消预约" width="420px">
      <p class="dialog-tip">
        取消后该时段的名额会释放给其他老人，此操作不可撤销。
      </p>
      <el-input
        v-model="cancelReason"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        placeholder="请填写取消原因"
      />
      <template #footer>
        <el-button @click="cancelVisible = false">返回</el-button>
        <el-button type="danger" :loading="cancelling" @click="confirmCancel">确认取消</el-button>
      </template>
    </el-dialog>
    <!-- 提交评价 -->
    <el-dialog v-model="reviewVisible" title="评价本次服务" width="440px">
      <p class="dialog-tip">评价提交后不能修改。评分与服务表现直接相关，请如实填写。</p>
      <el-rate v-model="reviewForm.rating" :max="5" show-text
               :texts="['很不满意', '不满意', '一般', '满意', '很满意']" />
      <el-input
        v-model="reviewForm.content"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        class="review-text"
        placeholder="说说服务人员的表现（选填）"
      />
      <template #footer>
        <el-button @click="reviewVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="confirmReview">提交评价</el-button>
      </template>
    </el-dialog>

    <!-- 反馈未完成 -->
    <el-dialog v-model="returnVisible" title="反馈未完成事项" width="440px">
      <p class="dialog-tip">
        提交后订单会回到服务人员处重新处理。首次开工记录会保留，不会当作从未开始。
      </p>
      <el-input
        v-model="returnReason"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        placeholder="请说明哪些地方没有做到"
      />
      <template #footer>
        <el-button @click="returnVisible = false">返回</el-button>
        <el-button type="warning" :loading="acting" @click="confirmReturn">提交反馈</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  cancelOrder,
  confirmOrder,
  createReview,
  listMyOrders,
  listMyOrdersPaged,
  listMyReviewsForOrders,
  returnOrder
} from '@/api/order'
import OrderDetailDrawer from '@/views/shared/OrderDetailDrawer.vue'

const loading = ref(false)
let orderLoadId = 0
const loadError = ref('')
const orders = ref([])
const total = ref(0)
const query = ref({ pageNum: 1, pageSize: 20 })

// 共用详情抽屉
const detailVisible = ref(false)
const detailOrderId = ref(null)
// 抽屉加载完详情后透过来的数据。动作清单按**它的状态**算——
// 不能从列表行取：翻页后那一行可能已经不在当前页。
const detailCache = ref(null)

const cancelVisible = ref(false)
const cancelling = ref(false)
const cancelReason = ref('')
const currentOrder = ref(null)

const returnVisible = ref(false)
const returnReason = ref('')
const acting = ref(false)
const reviewVisible = ref(false)
const reviewTarget = ref(null)
const reviewForm = ref({ rating: 5, content: '' })
const reviewedIds = ref([])
const reviewReadError = ref('')

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

onMounted(load)

async function loadReviewed(requestId = orderLoadId) {
  reviewReadError.value = ''
  const ids = orders.value.filter(order => order.status === 'COMPLETED').map(order => String(order.id))
  if (!ids.length) { reviewedIds.value = []; return }
  try {
    const res = await listMyReviewsForOrders(ids)
    if (requestId !== orderLoadId) return
    reviewedIds.value = (res.data || []).map((r) => String(r.orderId))
  } catch (e) {
    if (requestId !== orderLoadId) return
    reviewedIds.value = []
    reviewReadError.value = '评价状态读取失败，请刷新后再评价'
  }
}

/**
 * 加载我的订单（服务端分页）。
 *
 * <p>翻页只改 pageNum，**不带任何归属参数**——elderId 由会话决定。
 * 一次拉全量再由浏览器过滤既慢，也会随数据增长撑爆页面。
 */
async function load(pageNum, recoverEmpty = true) {
  if (Number.isSafeInteger(pageNum) && pageNum > 0) {
    query.value.pageNum = pageNum
  }
  const requestId = ++orderLoadId
  const params = { ...query.value }
  loading.value = true
  loadError.value = ''
  try {
    const res = await listMyOrdersPaged({
      pageNum: params.pageNum,
      pageSize: params.pageSize
    })
    if (requestId !== orderLoadId) return
    orders.value = res.data?.records || []
    total.value = res.data?.total || 0
    const lastPage = Math.max(1, Math.ceil(total.value / params.pageSize))
    if (recoverEmpty && !orders.value.length && params.pageNum > lastPage) {
      await load(lastPage, false)
      return
    }
    await loadReviewed(requestId)
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

function openDetail(row) {
  detailOrderId.value = String(row.id)
  detailCache.value = null
  detailVisible.value = true
}

function onDetailLoaded(detail) {
  detailCache.value = detail
}

/**
 * 老人在详情里能做什么。
 *
 * <p>只有取消与确认完成是老人自己的动作；分派、接单那些属于管理员与工作人员，
 * 放到这里就会出现「点了就 409」的按钮。
 */
const elderActions = computed(() => {
  const d = detailCache.value
  if (!d) {
    return []
  }
  const list = []
  if (d.status === 'PENDING_CONFIRMATION') {
    list.push({ key: 'confirm', label: '确认完成', type: 'primary' })
  }
  if (['PENDING_ASSIGNMENT', 'ASSIGNED', 'ACCEPTED'].includes(d.status)) {
    list.push({ key: 'cancel', label: '取消预约', type: 'danger' })
  }
  return list
})

async function runDetailAction(action, trigger) {
  const handlers = {
    confirm: async (d) => {
      // 带上详情里读到的版本：不带等于绕过乐观锁
      await confirmOrder(d.id, d.version)
      ElMessage.success('已确认完成')
    },
    cancel: async (d) => {
      await cancelOrder(d.id, '老人在详情中取消', d.version)
      ElMessage.success('已取消')
    }
  }
  await trigger(action.key, handlers[action.key])
}

function statusLabel(status) {
  return STATUS_LABELS[status] || status
}

function statusType(status) {
  return STATUS_TYPES[status] || 'info'
}

/** 服务开始前的三个状态可取消 */
function cancellable(row) {
  return ['PENDING_ASSIGNMENT', 'ASSIGNED', 'ACCEPTED'].includes(row.status)
}

async function confirmComplete(row) {
  acting.value = true
  try {
    await confirmOrder(row.id, row.version)
    ElMessage.success('已确认完成')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '确认失败，请刷新后重试')
  } finally {
    acting.value = false
  }
}

function openReturn(row) {
  currentOrder.value = row
  returnReason.value = ''
  returnVisible.value = true
}

async function confirmReturn() {
  if (!returnReason.value.trim()) {
    ElMessage.warning('请说明未完成的事项')
    return
  }
  acting.value = true
  try {
    await returnOrder(currentOrder.value.id, returnReason.value.trim(), currentOrder.value.version)
    ElMessage.success('已反馈，服务人员会重新处理')
    returnVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '提交失败，请刷新后重试')
  } finally {
    acting.value = false
  }
}

function openReview(row) {
  reviewTarget.value = row
  reviewForm.value = { rating: 5, content: '' }
  reviewVisible.value = true
}

async function confirmReview() {
  if (!reviewForm.value.rating) {
    ElMessage.warning('请先评分')
    return
  }
  acting.value = true
  try {
    await createReview(reviewTarget.value.id, {
      rating: reviewForm.value.rating,
      content: reviewForm.value.content.trim() || ''
    })
    ElMessage.success('评价已提交，不能修改')
    reviewVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '评价失败，请刷新后重试')
  } finally {
    acting.value = false
  }
}

function formatTime(row) {
  const fmt = (v) => (v ? v.replace('T', ' ').slice(5, 16) : '')
  return `${fmt(row.slotStartSnapshot)} — ${fmt(row.slotEndSnapshot).slice(-5)}`
}

function openCancel(row) {
  currentOrder.value = row
  cancelReason.value = ''
  cancelVisible.value = true
}

async function confirmCancel() {
  if (!cancelReason.value.trim()) {
    ElMessage.warning('请填写取消原因')
    return
  }
  cancelling.value = true
  try {
    await cancelOrder(currentOrder.value.id, cancelReason.value.trim(), currentOrder.value.version)
    ElMessage.success('已取消')
    cancelVisible.value = false
    await load()
  } catch (e) {
    // 状态可能已被他人改变，提示刷新而不是显示成功
    ElMessage.error(e?.message || '取消失败，请刷新后重试')
  } finally {
    cancelling.value = false
  }
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

.page-note {
  margin-bottom: 12px;
}

.review-text {
  margin-top: 12px;
}

.dialog-tip {
  font-size: 13px;
  color: var(--el-text-color-regular);
  margin-bottom: 12px;
  line-height: 1.6;
}

.muted {
  color: var(--el-text-color-secondary);
}
</style>
