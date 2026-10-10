<template>
  <el-card>
    <template #header>
      <div class="card-header">
        <span class="card-title">{{ title }}</span>
        <el-button :loading="loading" @click="load()">刷新</el-button>
      </div>
    </template>

    <el-alert
      v-if="role === 'ELDER'"
      type="info"
      :closable="false"
      class="tip"
      title="已提交的求助会进入社区待办。可在这里查看分派、接收和处理进度。"
    />

    <el-form inline @submit.prevent="load(1)">
      <el-form-item label="范围"><el-select v-model="filters.scope" style="width:140px" @change="load(1)"><el-option label="当前事项" value="CURRENT"/><el-option label="历史事项" value="HISTORY"/><el-option label="全部事项" value="ALL"/></el-select></el-form-item>
      <el-form-item label="状态"><el-select v-model="filters.status" clearable style="width:130px"><el-option v-for="(label,value) in statusLabels" :key="value" :value="value" :label="label"/></el-select></el-form-item>
      <el-form-item label="来源"><el-select v-model="filters.sourceType" clearable style="width:140px"><el-option label="居民求助" value="HELP_CALL"/><el-option label="健康待核实" value="HEALTH_ALERT"/></el-select></el-form-item>
      <el-form-item v-if="filters.unresponded" label="首次响应"><el-tag closable @close="filters.unresponded=false;load(1)">未响应</el-tag></el-form-item>
      <el-button native-type="submit" type="primary" :disabled="loading">查询</el-button>
    </el-form>
    <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false"><el-button link @click="load()">重试</el-button></el-alert>
    <el-table v-else :data="list" v-loading="loading" empty-text="没有符合条件的事项" @row-click="goDetail">
      <el-table-column prop="eventNo" label="事项编号" min-width="180" />
      <el-table-column prop="sourceLabel" label="来源" width="100" />
      <el-table-column label="老人" width="100" prop="elderName" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)" size="small">{{ row.statusLabel }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.priority === 'HIGH'" type="danger" size="small">高</el-tag>
          <span v-else class="muted">普通</span>
        </template>
      </el-table-column>
      <el-table-column prop="occurrenceCount" label="发生次数" width="90" />
      <el-table-column label="处理人" width="100" prop="handlerName">
        <template #default="{ row }">
          <span v-if="row.handlerName">{{ row.handlerName }}</span>
          <span v-else class="muted">未分派</span>
        </template>
      </el-table-column>
      <el-table-column label="首次响应" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.firstResponseOverdue" type="danger" size="small">已逾期</el-tag>
          <span v-else class="muted">正常</span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="170" />
    </el-table>
    <ListPagination :page="page" :size="size" :total="total" :loading="loading" @page="load" @size="changeSize"/>
    <section v-if="role==='SERVICE_STAFF'" class="participation-section"><h3>我参与过的事项</h3><p class="care-secondary">仅显示本人动作与事项摘要，转出后不提供当前联系资料或详情入口。</p><el-alert v-if="historyError" :title="historyError" type="error" :closable="false"><el-button link @click="loadHistory()">重试</el-button></el-alert><el-table v-else :data="history" v-loading="historyLoading" empty-text="暂无参与记录"><el-table-column prop="eventNo" label="事项编号" min-width="200" show-overflow-tooltip/><el-table-column label="来源"><template #default="{row}">{{row.sourceType==='HELP_CALL'?'居民求助':'健康待核实'}}</template></el-table-column><el-table-column label="当前状态"><template #default="{row}">{{statusLabels[row.currentStatus]}}</template></el-table-column><el-table-column prop="action" label="本人动作"/><el-table-column prop="actionAt" label="动作时间" min-width="180"/></el-table><ListPagination :page="historyPage" :size="historySize" :total="historyTotal" :loading="historyLoading" @page="loadHistory" @size="changeHistorySize"/></section>
  </el-card>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import request from '@/utils/request'
import ListPagination from '@/components/ListPagination.vue'
import { getStoredUser } from '@/utils/auth'

const props = defineProps({
  title: { type: String, default: '待办事项' }
})

const router = useRouter()
const route=useRoute()
const page=ref(1),size=ref(20),total=ref(0),loadError=ref('')
const filters=reactive({scope:'CURRENT',status:typeof route.query.status==='string'?route.query.status:'',sourceType:'',unresponded:route.query.unresponded==='true'})
const statusLabels={PENDING:'待分派',ASSIGNED:'待接收',PROCESSING:'处理中',CLOSED:'已关闭',CANCELLED:'已取消'}
const history=ref([]),historyPage=ref(1),historySize=ref(20),historyTotal=ref(0),historyLoading=ref(false),historyError=ref('')
async function loadHistory(next=historyPage.value,recover=true){if(role.value!=='SERVICE_STAFF')return;historyLoading.value=true;historyError.value=''
  try{const data=(await request.get('/alert-events/my-participations/paged',{params:{pageNum:Number.isSafeInteger(next)&&next>0?next:historyPage.value,pageSize:historySize.value}})).data;history.value=data.records;historyTotal.value=data.total;historyPage.value=data.pageNum;const last=Math.max(1,Math.ceil(historyTotal.value/historySize.value));if(recover&&!history.value.length&&historyPage.value>last)return await loadHistory(last,false)}catch(e){historyError.value=e?.response?.data?.message||'参与历史读取失败，请重试'}finally{historyLoading.value=false}}
function changeHistorySize(value){historySize.value=value;return loadHistory(1)}
const loading = ref(false)
const list = ref([])
const role = computed(() => getStoredUser()?.role || '')
let timer = null

function statusTagType(status) {
  return {
    PENDING: 'warning',
    ASSIGNED: 'info',
    PROCESSING: 'primary',
    CLOSED: 'success',
    CANCELLED: 'info'
  }[status] || 'info'
}

async function load(next=page.value,recover=true) {
  if(loading.value)return
  loading.value = true
  loadError.value=''
  try {
    const res = await request.get('/alert-events/paged',{params:{...filters,pageNum:Number.isSafeInteger(next)&&next>0?next:page.value,pageSize:size.value}})
    list.value=res.data.records;total.value=res.data.total;page.value=res.data.pageNum
    const last=Math.max(1,Math.ceil(total.value/size.value))
    if(recover&&!list.value.length&&page.value>last){loading.value=false;return await load(last,false)}
  } catch (error) {
    loadError.value=error?.response?.data?.message||'事项读取失败，请重试'
  } finally {
    loading.value = false
  }
}
function changeSize(value){size.value=value;return load(1)}

function goDetail(row) {
  router.push({ name: 'AlertDetail', params: { eventId: row.id } })
}

// 轮询待办：页面隐藏时暂停，回到前台立即补查。
// 组件卸载必须清掉定时器，否则离开页面后还在发请求。
function startPolling() {
  stopPolling()
  timer = window.setInterval(() => {
    if (!document.hidden) {
      load()
    }
  }, 15000)
}

function stopPolling() {
  if (timer) {
    window.clearInterval(timer)
    timer = null
  }
}

function onVisibilityChange() {
  if (!document.hidden) {
    load()
  }
}

onMounted(() => {
  load()
  loadHistory()
  startPolling()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 16px;
  font-weight: 500;
}

.tip {
  margin-bottom: 16px;
}
.participation-section{margin-top:32px}.participation-section h3{font-size:16px;margin-bottom:8px}.participation-section p{margin-bottom:18px}

.muted {
  color: #c0c4cc;
}

:deep(.el-table__row) {
  cursor: pointer;
}
</style>
