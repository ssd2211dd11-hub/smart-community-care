<template>
  <el-drawer
    v-model="visible"
    title="订单详情"
    size="520px"
    @closed="onClosed"
  >
    <div v-loading="loading">
      <el-alert
        v-if="errorMessage"
        :title="errorMessage"
        type="warning"
        show-icon
        :closable="false"
        class="detail-alert"
      />

      <template v-if="detail">
        <!-- 快照区：这些字段是下单时定格的，目录后来改名改价都不影响它们 -->
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="服务">
            {{ detail.serviceNameSnapshot }}
          </el-descriptions-item>
          <el-descriptions-item label="参考金额">
            {{ detail.priceSnapshot }} 元
            <span class="hint">（参考价格，不代表收入）</span>
          </el-descriptions-item>
          <el-descriptions-item label="预约时段">
            {{ formatTime(detail) }}
            <div v-if="detail.status==='ACCEPTED'" class="hint">到预约开始时间后可开工；提交服务结果后由老人确认完成。</div>
          </el-descriptions-item>
          <el-descriptions-item label="联系人">
            {{ detail.contactName }} · {{ detail.contactPhone }}
          </el-descriptions-item>
          <el-descriptions-item label="地址">
            {{ detail.addressSnapshot || '—' }}
          </el-descriptions-item>
          <!--
            责任人：取「当前」受派人，不是时间线里出现过的某个历史姓名。
            未分派时后端不返回这两个字段，这里显式显示「未分派」而不是空白。
          -->
          <el-descriptions-item label="当前责任人">
            <span v-if="detail.staffName">{{ detail.staffName }}</span>
            <el-tag v-else type="info" size="small">未分派</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="当前状态">
            <el-tag :type="statusType(detail.status)" size="small">
              {{ statusLabel(detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="服务结果">
            {{ detail.serviceResult || '尚未提交' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.cancelledAt" label="取消时间">
            {{ formatDateTime(detail.cancelledAt) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.cancelReason" label="取消原因">
            {{ detail.cancelReason }}
          </el-descriptions-item>
        </el-descriptions>

        <!--
          合法动作区：没有可执行动作时显式说明，而不是摆一个必然失败的按钮。
          「尚未完成后续动作时不显示虚假的成功按钮」。
        -->
        <h4 class="section-title">可执行操作</h4>
        <div v-if="detail && availableActions.length" class="action-row">
          <slot
            name="actions"
            :detail="detail"
            :actions="availableActions"
            :trigger="trigger"
          />
        </div>
        <p v-else-if="detail" class="no-action">
          当前状态「{{ statusLabel(detail.status) }}」在你这个角色下没有可执行的操作。
        </p>
      </template>

      <el-empty v-else-if="!loading && !errorMessage" description="未找到订单" />
    </div>
  </el-drawer>
</template>

<script setup>
/**
 * 订单详情展示与刷新。
 * 动作通过父页面插槽传入；对象权限由后端校验，响应只包含页面字段，ID使用字符串。
 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getOrderView } from '@/api/order'

const props = defineProps({
  /** 详情抽屉是否打开 */
  modelValue: { type: Boolean, default: false },
  /** 要查看的订单 ID（字符串） */
  orderId: { type: [String, Number], default: null },
  /**
   * 当前角色在此订单上可执行的动作定义。
   *
   * <p><b>由父页面传入，不在本组件里判断。</b>三种角色的合法动作本来就不同
   * （管理员可分派、老人可取消、工作人员可接单），
   * 组件自己推断会出现「算了半天给出一个点了就 409 的按钮」。
   *
   * <p>格式：`[{ key, label, type }]`。为空数组时显示「没有可执行的操作」。
   */
  availableActions: { type: Array, default: () => [] }
})

const emit = defineEmits(['update:modelValue', 'changed', 'detail-loaded'])

const visible = ref(props.modelValue)
const loading = ref(false)
const detail = ref(null)
const errorMessage = ref('')

watch(() => props.modelValue, (v) => {
  visible.value = v
  if (v) {
    load()
  }
})

watch(visible, (v) => {
  emit('update:modelValue', v)
})

async function load() {
  if (!props.orderId) {
    return
  }
  loading.value = true
  errorMessage.value = ''
  detail.value = null
  try {
    const res = await getOrderView(props.orderId)
    detail.value = res.data
    // 把详情透给父页面：动作清单要按**详情里的状态**算，
    // 不能从列表行取——翻页后那一行可能已经不在当前页了。
    emit('detail-loaded', res.data)
  } catch (e) {
    // 404 覆盖「不存在」与「无权访问」两种情况——
    // 后端刻意不区分，避免泄露订单是否存在。这里照原样呈现。
    errorMessage.value = e?.message || '无法查看该订单'
  } finally {
    loading.value = false
  }
}

function onClosed() {
  detail.value = null
  errorMessage.value = ''
}

defineExpose({ reload: load })

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
  PENDING_ASSIGNMENT: 'warning',
  ASSIGNED: 'primary',
  ACCEPTED: 'primary',
  IN_SERVICE: 'success',
  PENDING_CONFIRMATION: 'success',
  COMPLETED: 'info',
  CANCELLED: 'info',
  TERMINATED: 'danger'
}

function statusLabel(status) {
  return STATUS_LABELS[status] || status || '未知'
}

function statusType(status) {
  return STATUS_TYPES[status] || 'info'
}

/**
 * 动作触发器：父页面在插槽里调用它执行动作，成功后自动刷新详情。
 *
 * <p>执行完必须刷新——动作会改变状态（分派后变成「待接单」），
 * 不刷新就会显示旧状态，并让同一动作看起来还能再点一次。
 *
 * @param {string} actionKey 父页面在 availableActions 里定义的 key
 * @param {Function} handler 实际执行动作的函数
 */
async function trigger(actionKey, handler) {
  if (typeof handler !== 'function') {
    ElMessage.warning('该动作尚未接入')
    return
  }
  try {
    await handler(detail.value)
    // 通知父页面刷新列表，再刷新详情——顺序保证列表与详情一致
    emit('changed', { actionKey, orderId: detail.value?.id })
    await load()
  } catch (e) {
    // 动作失败由父页面的 handler 负责提示；这里不重复弹错
    ElMessage.error(e?.response?.data?.message || e?.message || '操作失败')
  }
}

function formatTime(order) {
  const fmt = (v) => (v ? v.replace('T', ' ').slice(5, 16) : '')
  return `${fmt(order.slotStartSnapshot)} — ${fmt(order.slotEndSnapshot).slice(-5)}`
}

function formatDateTime(value) {
  return value ? value.replace('T', ' ').slice(0, 19) : ''
}
</script>

<style scoped>
.section-title {
  margin: 18px 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.action-row {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.no-action {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.detail-alert {
  margin-bottom: 12px;
}
</style>
