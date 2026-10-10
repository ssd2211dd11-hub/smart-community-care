<template>
  <el-card class="help-call">
    <template #header><span class="card-title">提交求助</span></template>

    <el-alert
      type="warning"
      :closable="false"
      class="tip"
      title="提交后可在「我的事项」查看进度。社区分派并接收后，处理状态会同步更新。"
    />

    <el-alert v-if="pending" type="warning" :closable="false" class="tip" title="这次求助尚未确认。确认原提交会沿用原内容，不会重复创建。">
      <el-button :loading="submitting" @click="handleSubmit">确认原提交</el-button>
      <el-button :disabled="submitting" @click="abandonPending">不再跟踪</el-button>
    </el-alert>

    <el-form ref="formRef" :model="form" :rules="rules" :disabled="submitting || !!pending" label-width="90px" v-loading="submitting" @submit.prevent>
      <el-form-item label="求助类型" prop="callType">
        <el-select v-model="form.callType" placeholder="请选择">
          <el-option label="身体不适" value="MEDICAL" />
          <el-option label="跌倒受伤" value="FALL" />
          <el-option label="家中紧急情况" value="URGENT" />
          <el-option label="需要帮助" value="OTHER" />
        </el-select>
      </el-form-item>

      <el-form-item label="联系电话" prop="contactPhone">
        <el-input v-model="form.contactPhone" maxlength="20" />
      </el-form-item>

      <el-form-item label="补充说明" prop="description">
        <el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" show-word-limit />
      </el-form-item>

      <el-form-item>
        <el-button type="danger" :loading="submitting" @click="handleSubmit">提交求助</el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { submitHelpCall } from '@/api/alert'

const router = useRouter()
const formRef = ref(null)
const submitting = ref(false)
const pending = ref(null)
const previouslyUncertain = ref(false)

const form = reactive({ callType: '', contactPhone: '', description: '' })

const rules = {
  callType: [{ required: true, message: '请选择求助类型', trigger: 'change' }],
  contactPhone: [
    { required: true, message: '请填写联系电话', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ]
}

async function handleSubmit() {
  if (submitting.value) return
  if (pending.value) return sendSnapshot(true)
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid || submitting.value) return

  pending.value = Object.freeze({
    callType: form.callType,
    contactPhone: form.contactPhone,
    description: form.description,
    requestId: newRequestId()
  })
  return sendSnapshot(false)
}

async function sendSnapshot(isRetry) {
  submitting.value = true
  let saved = false
  try {
    await submitHelpCall(pending.value)
    pending.value = null
    previouslyUncertain.value = false
    saved = true
    ElMessage.success('求助已提交，等待社区分派')
  } catch (error) {
    const status = error.response?.status
    if (!error.response || status >= 500 || (isRetry && previouslyUncertain.value)) {
      previouslyUncertain.value = true
      ElMessage.warning('提交结果尚未确认，请确认原提交或在「我的事项」查看')
    } else {
      pending.value = null
      previouslyUncertain.value = false
    }
  } finally {
    submitting.value = false
  }
  if (saved) await router.push('/elder/my-alerts')
}

function abandonPending() {
  if (submitting.value) return
  pending.value = null
  previouslyUncertain.value = false
  ElMessage.info('已不再跟踪。若求助已创建，仍可在「我的事项」查看；这不是撤销求助。')
}

function newRequestId() {
  if (window.crypto?.randomUUID) {
    return window.crypto.randomUUID()
  }
  return `help-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}
</script>

<style scoped>
.help-call { max-width: 640px; }
.card-title { font-size: 16px; font-weight: 500; }
.tip { margin-bottom: 16px; }
</style>
