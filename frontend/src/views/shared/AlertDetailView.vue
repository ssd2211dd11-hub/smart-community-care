<template>
  <div class="alert-detail" v-loading="loading">
    <el-card v-if="detail">
      <template #header>
        <div class="card-header">
          <span class="card-title">事项详情 · {{ detail.eventNo }}</span>
          <el-button @click="$router.back()">返回</el-button>
        </div>
      </template>

      <el-descriptions :column="2" border>
        <el-descriptions-item label="来源">{{ detail.sourceLabel }}</el-descriptions-item>
        <el-descriptions-item label="老人">{{ detail.elderName }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(detail.status)" size="small">{{ detail.statusLabel }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="优先级">
          {{ detail.priority === 'HIGH' ? '高' : '普通' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.ruleCode" label="命中规则">
          {{ detail.ruleCode }}
        </el-descriptions-item>
        <el-descriptions-item label="发生次数">{{ detail.occurrenceCount }}</el-descriptions-item>
        <el-descriptions-item label="处理人">
          {{ detail.handlerName || '未分派' }}
        </el-descriptions-item>
        <el-descriptions-item label="响应时限">{{ detail.responseDueAt || '—' }}</el-descriptions-item>
        <el-descriptions-item label="首次接收">
          {{ detail.firstAcceptedAt || '尚未接收' }}
        </el-descriptions-item>
        <el-descriptions-item label="是否逾期">
          <el-tag v-if="detail.firstResponseOverdue" type="danger" size="small">已逾期</el-tag>
          <span v-else>正常</span>
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.callType" label="求助类型">{{ detail.callType }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.contactPhone" label="联系电话">
          {{ detail.contactPhone }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.description" label="说明" :span="2">
          {{ detail.description }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detail.closeResult" label="关闭结果" :span="2">
          {{ detail.closeResult }} · {{ detail.closeNote }}
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="availableActions.length" class="actions">
        <div class="actions-title">可执行操作</div>
        <el-button
          v-for="act in availableActions"
          :key="act.key"
          :type="act.type"
          :loading="acting === act.key"
          @click="handleAction(act)"
        >
          {{ act.label }}
        </el-button>
      </div>
      <el-alert
        v-else
        type="info"
        :closable="false"
        class="no-action"
        title="当前状态下你没有可执行的操作。"
      />
    </el-card>

    <el-card v-if="detail && detail.timeline && detail.timeline.length" class="timeline-card">
      <template #header><span class="card-title">处理时间线</span></template>

      <el-timeline>
        <el-timeline-item
          v-for="(log, i) in detail.timeline"
          :key="i"
          :timestamp="log.createdAt"
          :type="timelineType(log.action)"
        >
          <div class="log-action">{{ actionLabel(log.action) }}</div>
          <div class="log-meta">
            <span v-if="log.fromStatus && log.toStatus">
              {{ log.fromStatus }} → {{ log.toStatus }}
            </span>
            <span v-if="log.operatorName"> · 操作人 {{ log.operatorName }}</span>
            <span v-if="log.operatorSource === 'SYSTEM'"> · 系统动作</span>
          </div>
          <div v-if="log.reason" class="log-reason">{{ log.reason }}</div>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAlertDetail, claimAlert, assignAlert, acceptAlert,
  transferAlert, closeAlert, cancelAlert
} from '@/api/alert'
import { getStoredUser } from '@/utils/auth'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const acting = ref('')
const detail = ref(null)

const currentUser = computed(() => getStoredUser() || {})
const role = computed(() => currentUser.value.role || '')

/**
 * 按当前角色与状态算出可执行动作。
 *
 * <p>这里只是"不显示不该点的按钮"，不是权限控制——
 * 后端每个动作都会独立校验权限与状态。
 */
const availableActions = computed(() => {
  const d = detail.value
  if (!d) {
    return []
  }
  const isAdmin = role.value === 'COMMUNITY_ADMIN'
  const isHandler = d.handlerId && d.handlerId === currentUser.value.userId
  const actions = []

  if (d.status === 'PENDING' && isAdmin) {
    actions.push({ key: 'claim', label: '认领', type: 'primary', action: 'claim' })
    actions.push({ key: 'assign', label: '分派', type: 'primary', action: 'assign' })
  }
  if (d.status === 'ASSIGNED' && isHandler) {
    actions.push({ key: 'accept', label: '接收', type: 'primary', action: 'accept' })
  }
  if ((d.status === 'ASSIGNED' || d.status === 'PROCESSING') && isAdmin) {
    actions.push({ key: 'transfer', label: '转派', type: 'warning', action: 'transfer' })
  }
  if (d.status === 'PROCESSING' && (isAdmin || isHandler)) {
    actions.push({ key: 'close', label: '关闭', type: 'success', action: 'close' })
  }
  if (role.value === 'ELDER' && d.status === 'PENDING' && d.sourceType === 'HELP_CALL' && !d.handlerId) {
    actions.push({ key: 'cancel', label: '撤销', type: 'info', action: 'cancel' })
  }
  return actions
})

function statusTagType(status) {
  return {
    PENDING: 'warning',
    ASSIGNED: 'info',
    PROCESSING: 'primary',
    CLOSED: 'success',
    CANCELLED: 'info'
  }[status] || 'info'
}

function timelineType(action) {
  return {
    CREATE: 'primary',
    CLAIM: 'primary',
    ASSIGN: 'warning',
    ACCEPT: 'primary',
    TRANSFER: 'warning',
    APPEND_EVIDENCE: 'info',
    CLOSE: 'success',
    CANCEL: 'info'
  }[action] || 'info'
}

function actionLabel(action) {
  return {
    CREATE: '创建',
    CLAIM: '认领',
    ASSIGN: '分派',
    ACCEPT: '接收',
    TRANSFER: '转派',
    APPEND_EVIDENCE: '追加证据',
    CLOSE: '关闭',
    CANCEL: '撤销'
  }[action] || action
}

async function load() {
  loading.value = true
  try {
    const res = await getAlertDetail(route.params.eventId)
    detail.value = res.data
  } catch (error) {
    detail.value = null
  } finally {
    loading.value = false
  }
}

async function handleAction(act) {
  const key = act.key
  acting.value = key
  try {
    if (key === 'claim') {
      await claimAlert(detail.value.id, detail.value.version)
    } else if (key === 'accept') {
      await acceptAlert(detail.value.id, detail.value.version)
    } else if (key === 'assign') {
      const id = await askAssignee()
      if (!id) return
      await assignAlert(detail.value.id, id, undefined, detail.value.version)
    } else if (key === 'transfer') {
      const res = await askTransfer()
      if (!res) return
      await transferAlert(detail.value.id, res.handlerId, res.reason, detail.value.version)
    } else if (key === 'close') {
      const res = await askClose()
      if (!res) return
      await closeAlert(detail.value.id, res.result, res.note, detail.value.version)
    } else if (key === 'cancel') {
      const { value } = await ElMessageBox.prompt('请填写撤销原因', '撤销求助', {
        confirmButtonText: '确认撤销',
        cancelButtonText: '取消'
      })
      if (!value) return
      await cancelAlert(detail.value.id, value, detail.value.version)
    }
    ElMessage.success('操作成功')
    await load()
  } catch (error) {
    // 409 版本冲突：后端已提示"请刷新"，这里把详情刷回来
    if (error.response?.status === 409) {
      ElMessage.warning('事项状态已变化，已为你刷新')
      await load()
    }
  } finally {
    acting.value = ''
  }
}

/**
 * 分派目标选择。
 *
 * <p>首版用输入账号 ID 而非做人员下拉：演示规模下人员少，
 * 做成下拉还要额外拉账号列表接口并做角色过滤，收益不抵复杂度。
 */
async function askAssignee() {
  try {
    const { value } = await ElMessageBox.prompt(
      '请输入处理人的账号 ID',
      '分派事项',
      { confirmButtonText: '确认分派', cancelButtonText: '取消' }
    )
    const id = String(value || '').trim()
    if (!/^[1-9]\d*$/.test(id)) {
      ElMessage.warning('请输入有效的账号 ID')
      return null
    }
    return id
  } catch (e) {
    return null
  }
}

async function askTransfer() {
  try {
    const { value: handlerId } = await ElMessageBox.prompt(
      '请输入新处理人的账号 ID',
      '转派事项',
      { confirmButtonText: '下一步', cancelButtonText: '取消' }
    )
    const id = String(handlerId || '').trim()
    if (!/^[1-9]\d*$/.test(id)) {
      ElMessage.warning('请输入有效的账号 ID')
      return null
    }
    const { value: reason } = await ElMessageBox.prompt('请填写转派原因', '转派原因', {
      confirmButtonText: '确认转派',
      cancelButtonText: '取消'
    })
    if (!reason || !reason.trim()) {
      ElMessage.warning('转派必须填写原因')
      return null
    }
    return { handlerId: id, reason: reason.trim() }
  } catch (e) {
    return null
  }
}

async function askClose() {
  try {
    const { value: result } = await ElMessageBox.prompt(
      '请输入关闭结果：RESOLVED（已处理）/ FALSE_ALARM（误报）/ DATA_CORRECTION（数据纠错）',
      '关闭事项',
      { confirmButtonText: '下一步', cancelButtonText: '取消' }
    )
    const normalized = String(result || '').trim().toUpperCase()
    const allowed = ['RESOLVED', 'FALSE_ALARM', 'DATA_CORRECTION']
    if (!allowed.includes(normalized)) {
      ElMessage.warning(`结果只能是 ${allowed.join(' / ')}`)
      return null
    }
    const { value: note } = await ElMessageBox.prompt('请填写处理说明', '处理说明', {
      confirmButtonText: '确认关闭',
      cancelButtonText: '取消'
    })
    if (!note || !note.trim()) {
      ElMessage.warning('关闭必须填写处理说明')
      return null
    }
    return { result: normalized, note: note.trim() }
  } catch (e) {
    return null
  }
}

onMounted(load)
</script>

<style scoped>
.alert-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 16px;
  font-weight: 500;
}

.actions {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
}

.actions-title {
  font-size: 14px;
  color: #606266;
  margin-bottom: 12px;
}

.actions .el-button {
  margin-right: 8px;
}

.no-action {
  margin-top: 16px;
}

.log-action {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

.log-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}

.log-reason {
  font-size: 13px;
  color: #606266;
  margin-top: 4px;
}
</style>
