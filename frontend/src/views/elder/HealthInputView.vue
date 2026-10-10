<template>
  <div class="health-input">
    <div class="care-page-heading"><div><span class="care-eyebrow">我的健康</span><h1>健康记录</h1><p>查询测量历史，区分手工录入与模拟采样。</p></div><el-button type="primary" @click="entryVisible=true">录入记录</el-button></div>
    <el-drawer v-model="entryVisible" title="录入健康记录" size="min(760px, 95vw)" :close-on-click-modal="false" :show-close="!submitting" :close-on-press-escape="!submitting">
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="card-title">录入健康记录</span>
          <el-radio-group v-model="form.mode" size="small">
            <el-radio-button value="CURRENT">当前测量</el-radio-button>
            <el-radio-button value="HISTORY">历史补录</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <el-alert
        v-if="form.mode === 'HISTORY'"
        type="warning"
        :closable="false"
        class="mode-tip"
        title="历史补录只影响历史统计，不会产生待核实事项，也不会触发告警。"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="110px"
        v-loading="submitting"
        @submit.prevent
      >
        <el-form-item label="测量场景">
          <el-select v-model="form.measureScene" placeholder="请选择" clearable>
            <el-option label="空腹" value="FASTING" />
            <el-option label="餐后" value="POSTPRANDIAL" />
            <el-option label="随机" value="RANDOM" />
            <el-option label="未知" value="UNKNOWN" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="form.mode === 'HISTORY'" label="测量时间">
          <el-date-picker
            v-model="form.measuredAt"
            type="datetime"
            placeholder="选择实际测量时间（不能晚于现在）"
            :disabled-date="disableFutureDate"
            value-format="YYYY-MM-DDTHH:mm:ss"
          />
        </el-form-item>

        <el-form-item label="心率" prop="heartRate" :error="fieldErrors.heartRate">
          <el-input-number v-model="form.heartRate" :min="1" :max="300" placeholder="次/分钟" />
          <span class="unit">bpm</span>
        </el-form-item>

        <el-form-item label="收缩压" prop="systolic" :error="fieldErrors.systolic">
          <el-input-number v-model="form.systolic" :min="1" :max="300" :precision="1" />
          <span class="unit">mmHg</span>
        </el-form-item>

        <el-form-item label="舒张压" prop="diastolic" :error="fieldErrors.diastolic">
          <el-input-number v-model="form.diastolic" :min="1" :max="200" :precision="1" />
          <span class="unit">mmHg</span>
        </el-form-item>

        <el-form-item label="血糖" prop="bloodSugar" :error="fieldErrors.bloodSugar">
          <el-input-number v-model="form.bloodSugar" :min="0.1" :max="50" :precision="2" />
          <span class="unit">mmol/L</span>
        </el-form-item>

        <el-form-item label="体温" prop="bodyTemperature" :error="fieldErrors.bodyTemperature">
          <el-input-number v-model="form.bodyTemperature" :min="30" :max="45" :precision="1" />
          <span class="unit">℃</span>
        </el-form-item>

        <el-form-item label="睡眠时长" prop="sleepHours" :error="fieldErrors.sleepHours">
          <el-input-number v-model="form.sleepHours" :min="0" :max="24" :precision="1" />
          <span class="unit">小时</span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
          <el-button @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    </el-drawer>
    <el-card class="recent-card">
      <template #header><span class="card-title">测量历史</span></template>
      <el-form inline @submit.prevent="loadRecent(1)"><el-form-item label="测量日期"><el-date-picker v-model="filters.fromDate" type="date" value-format="YYYY-MM-DD" style="width:150px" placeholder="开始日期"/><el-date-picker v-model="filters.toDate" type="date" value-format="YYYY-MM-DD" style="width:150px" placeholder="结束日期"/></el-form-item><el-form-item label="来源"><el-select v-model="filters.source" clearable style="width:130px"><el-option label="手工录入" value="MANUAL"/><el-option label="模拟采样" value="DEMO"/></el-select></el-form-item><el-form-item label="有效状态"><el-select v-model="filters.voided" clearable style="width:130px"><el-option label="有效" :value="false"/><el-option label="已作废" :value="true"/></el-select></el-form-item><el-button type="primary" native-type="submit" :disabled="loading">查询</el-button><el-button @click="loadRecent()" :disabled="loading">刷新</el-button></el-form>
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false"><el-button link @click="loadRecent()">重试</el-button></el-alert>

      <el-table v-else :data="recent" v-loading="loading" empty-text="没有符合条件的健康记录">
        <el-table-column prop="measuredAt" label="测量时间" min-width="160" />
        <el-table-column label="来源" width="100"><template #default="{row}">{{row.source==='DEMO'?'模拟采样':'手工录入'}}</template></el-table-column>
        <el-table-column label="方式" width="100"><template #default="{row}">{{row.mode==='HISTORY'?'历史补录':'当前测量'}}</template></el-table-column>
        <el-table-column prop="heartRate" label="心率" width="80" />
        <el-table-column label="血压" width="130">
          <template #default="{ row }">
            <span v-if="row.systolic && row.diastolic">{{ row.systolic }}/{{ row.diastolic }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="bloodSugar" label="血糖" width="90" />
        <el-table-column label="规则命中" min-width="180">
          <template #default="{ row }">
            <template v-if="row.anomalies && row.anomalies.length">
              <el-tag v-for="a in row.anomalies" :key="a.ruleCode" type="warning" size="small" class="anomaly-tag">
                {{ a.metricLabel }}{{ a.unit }}
              </el-tag>
            </template>
            <span v-else class="muted">未命中</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="!row.voided" link type="danger" @click="handleVoid(row)">作废</el-button>
            <el-tag v-else type="info" size="small">已作废</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <ListPagination :page="page" :size="size" :total="total" :loading="loading" @page="loadRecent" @size="changeSize"/>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createHealthRecord, voidHealthRecord } from '@/api/health'
import request from '@/utils/request'
import ListPagination from '@/components/ListPagination.vue'

const formRef = ref(null)
const submitting = ref(false)
const loading = ref(false)
const recent = ref([])
const entryVisible=ref(false),page=ref(1),size=ref(20),total=ref(0),loadError=ref('')
const filters=reactive({fromDate:'',toDate:'',source:'',voided:''})

// 后端返回的字段级错误，定位到对应输入框
const fieldErrors = reactive({})

const form = reactive({
  mode: 'CURRENT',
  measureScene: '',
  measuredAt: null,
  heartRate: null,
  systolic: null,
  diastolic: null,
  bloodSugar: null,
  bodyTemperature: null,
  sleepHours: null
})

const rules = {
  // 血压成对校验放在提交前统一做，这里只做单值范围提示
  systolic: [],
  diastolic: []
}

function disableFutureDate(date) {
  return date.getTime() > Date.now()
}

function resetForm() {
  form.measureScene = ''
  form.measuredAt = null
  form.heartRate = null
  form.systolic = null
  form.diastolic = null
  form.bloodSugar = null
  form.bodyTemperature = null
  form.sleepHours = null
  Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k])
}

function validateLocally() {
  Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k])
  const hasAny =
    form.heartRate !== null || form.systolic !== null || form.diastolic !== null ||
    form.bloodSugar !== null || form.bodyTemperature !== null || form.sleepHours !== null

  if (!hasAny) {
    ElMessage.warning('请至少填写一项健康指标')
    return false
  }
  // 血压成对：只填一个会被后端拒，但前端先提示更省事
  if ((form.systolic === null) !== (form.diastolic === null)) {
    ElMessage.warning('血压需成对填写：收缩压与舒张压必须同时提供')
    return false
  }
  if (form.systolic !== null && form.diastolic !== null && form.systolic <= form.diastolic) {
    ElMessage.warning('收缩压应大于舒张压')
    return false
  }
  return true
}

async function handleSubmit() {
  if (!validateLocally()) {
    return
  }
  submitting.value = true
  try {
    // requestId 为"一次提交"的标识：网络重试沿用同一个值，
    // 用户重新发起才换新的。这样既防重，又不会吞掉两次真实录入。
    const payload = {
      mode: form.mode,
      measureScene: form.measureScene || 'UNKNOWN',
      heartRate: form.heartRate,
      systolic: form.systolic,
      diastolic: form.diastolic,
      bloodSugar: form.bloodSugar,
      bodyTemperature: form.bodyTemperature,
      sleepHours: form.sleepHours,
      requestId: newRequestId()
    }
    if (form.mode === 'HISTORY') {
      if (!form.measuredAt) {
        ElMessage.warning('历史补录必须填写测量时间')
        submitting.value = false
        return
      }
      payload.measuredAt = form.measuredAt
    }

    await createHealthRecord(payload)
    ElMessage.success('录入成功')
    entryVisible.value=false
    resetForm()
    await loadRecent()
  } catch (error) {
    applyFieldErrors(error)
  } finally {
    submitting.value = false
  }
}

function applyFieldErrors(error) {
  const errs = error.response?.data?.fieldErrors
  if (errs && typeof errs === 'object') {
    Object.entries(errs).forEach(([k, v]) => {
      fieldErrors[k] = v
    })
  }
  // 业务错误（400）已由请求拦截器统一提示，这里不重复弹
}

function newRequestId() {
  if (window.crypto?.randomUUID) {
    return window.crypto.randomUUID()
  }
  return `req-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

async function handleVoid(row) {
  try {
    const { value } = await ElMessageBox.prompt(
      '作废后该记录不再参与统计，但已产生的处置记录会保留。',
      '作废原因',
      { confirmButtonText: '确认作废', cancelButtonText: '取消', inputPlaceholder: '请填写作废原因' }
    )
    if (!value || !value.trim()) {
      return
    }
    await voidHealthRecord(row.id, value.trim())
    ElMessage.success('已作废')
    await loadRecent()
  } catch (error) {
    // 取消或失败
  }
}

async function loadRecent(next=page.value,recover=true) {
  loading.value = true
  loadError.value=''
  try {
    const res=await request.get('/health-records/paged',{params:{...filters,fromDate:filters.fromDate||undefined,toDate:filters.toDate||undefined,voided:filters.voided===''?undefined:filters.voided,pageNum:Number.isSafeInteger(next)&&next>0?next:page.value,pageSize:size.value}})
    recent.value=res.data.records;page.value=res.data.pageNum;total.value=res.data.total
    const last=Math.max(1,Math.ceil(total.value/size.value));if(recover&&!recent.value.length&&page.value>last)return await loadRecent(last,false)
  } catch (error) {
    loadError.value=error?.response?.data?.message||'健康记录读取失败，请重试'
  } finally {
    loading.value = false
  }
}
function changeSize(value){size.value=value;return loadRecent(1)}

onMounted(loadRecent)
</script>

<style scoped>
.health-input {
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

.mode-tip {
  margin-bottom: 16px;
}

.unit {
  margin-left: 8px;
  color: #909399;
  font-size: 13px;
}

.recent-card :deep(.anomaly-tag) {
  margin-right: 4px;
}

.muted {
  color: #c0c4cc;
}
</style>
