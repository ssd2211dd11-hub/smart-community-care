<template>
  <div v-loading="loading">
    <div class="care-page-heading"><div><span class="care-eyebrow">居民照护 · 老人档案</span><h1>{{detail?.account.realName||'档案详情'}}</h1><p v-if="detail">{{detail.account.username}} · {{detail.account.phone||'尚未登记联系电话'}} · {{detail.account.enabled?'账号启用':'账号停用'}}</p></div><el-button @click="$router.push('/admin/elders')">返回档案</el-button></div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="loadHeader">重试</el-button></el-alert>
    <section v-else-if="detail" class="care-panel detail-panel">
      <el-tabs v-model="active" @tab-change="loadRelated(1)"><el-tab-pane label="基本资料" name="basic"/><el-tab-pane label="健康记录" name="health"/><el-tab-pane label="事项" name="alerts"/><el-tab-pane label="订单" name="orders"/><el-tab-pane label="设备" name="devices"/></el-tabs>
      <template v-if="active==='basic'">
        <div class="archive-identifiers"><span>账号ID {{detail.account.id}}</span><span>档案ID {{detail.profile.id}}</span><router-link :to="{path:'/admin/users',query:{keyword:detail.account.username}}">维护账号联系资料</router-link></div>
        <el-form :model="form" label-width="105px" :disabled="saving" class="archive-form">
          <el-form-item label="出生日期"><el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" :disabled-date="date=>date>new Date()" /></el-form-item>
          <el-form-item label="性别"><el-select v-model="form.gender" clearable><el-option label="男" value="male"/><el-option label="女" value="female"/><el-option label="其他" value="other"/></el-select></el-form-item>
          <el-form-item label="住址"><el-input v-model="form.address" maxlength="200"/></el-form-item>
          <el-form-item label="紧急联系人"><el-input v-model="form.emergencyContact" maxlength="50"/></el-form-item>
          <el-form-item label="联系人电话"><el-input v-model="form.emergencyPhone" maxlength="20"/></el-form-item>
          <el-form-item label="照护备注"><el-input v-model="form.careNote" type="textarea" :rows="4" maxlength="1000" show-word-limit/></el-form-item>
          <el-form-item><el-button type="primary" :loading="saving" @click="save">保存资料</el-button></el-form-item>
        </el-form>
      </template>
      <template v-else>
        <div class="related-heading"><p>仅查询这份老人档案关联的{{tabLabels[active]}}。</p><el-button @click="loadRelated()" :disabled="relatedLoading">刷新</el-button></div>
        <el-alert v-if="relatedError" :title="relatedError" type="error" :closable="false"><el-button link @click="loadRelated()">重试</el-button></el-alert>
        <el-table v-else :data="rows" v-loading="relatedLoading" empty-text="该档案暂无这类记录"><el-table-column v-for="column in columns" :key="column.prop" :prop="column.prop" :label="column.label" :min-width="column.width||120" show-overflow-tooltip :formatter="formatCell"/><el-table-column v-if="active==='alerts'||active==='orders'" label="操作" width="90"><template #default="{row}"><el-button link @click="openDetail(row)">详情</el-button></template></el-table-column></el-table>
        <ListPagination :page="page" :size="size" :total="total" :loading="relatedLoading" @page="loadRelated" @size="changeSize"/>
      </template>
    </section>
    <OrderDetailDrawer v-model="orderVisible" :order-id="orderId" />
  </div>
</template>
<script setup>
import { ref,reactive,computed,watch } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { elderDetail,updateElder } from '@/api/management'
import ListPagination from '@/components/ListPagination.vue'
import OrderDetailDrawer from '@/views/shared/OrderDetailDrawer.vue'
const route=useRoute(),router=useRouter(),detail=ref(null),loading=ref(false),error=ref(''),saving=ref(false),active=ref('basic')
const form=reactive({birthDate:'',gender:'',address:'',emergencyContact:'',emergencyPhone:'',careNote:''})
const rows=ref([]),page=ref(1),size=ref(20),total=ref(0),relatedLoading=ref(false),relatedError=ref(''),orderVisible=ref(false),orderId=ref(null)
const tabLabels={health:'健康记录',alerts:'事项',orders:'订单',devices:'设备'}
const columns=computed(()=>({health:[{prop:'measuredAt',label:'测量时间',width:180},{prop:'source',label:'来源'},{prop:'mode',label:'方式'},{prop:'heartRate',label:'心率'},{prop:'systolic',label:'收缩压'},{prop:'diastolic',label:'舒张压'},{prop:'bodyTemperature',label:'体温'}],alerts:[{prop:'eventNo',label:'事项编号',width:210},{prop:'sourceLabel',label:'来源'},{prop:'statusLabel',label:'状态'},{prop:'createdAt',label:'创建时间',width:180}],orders:[{prop:'orderNo',label:'订单号',width:210},{prop:'serviceNameSnapshot',label:'服务'},{prop:'slotStartSnapshot',label:'预约时间',width:180},{prop:'status',label:'状态'}],devices:[{prop:'deviceName',label:'设备'},{prop:'deviceType',label:'类型'},{prop:'onlineStatus',label:'状态'},{prop:'lastSeenAt',label:'最近心跳',width:180}]}[active.value]||[]))
function formatCell(row,column,value){if(value===null||value===undefined)return '—';return {MANUAL:'手工录入',DEMO:'模拟采样',CURRENT:'当前测量',HISTORY:'历史补录',LIGHT:'电灯',AIRCON:'空调',CURTAIN:'窗帘',HEALTH_SENSOR:'健康采样器',ONLINE:'在线',OFFLINE:'离线',DISABLED:'停用',PENDING_ASSIGNMENT:'待分派',ASSIGNED:'待接单',ACCEPTED:'待服务',IN_SERVICE:'服务中',PENDING_CONFIRMATION:'待确认',COMPLETED:'已完成',CANCELLED:'已取消',TERMINATED:'已终止'}[value]||String(value).replace('T',' ')}
async function loadHeader(){const id=route.params.elderId;loading.value=true;error.value='';detail.value=null;active.value='basic'
  try{const data=(await elderDetail(id)).data;if(id!==route.params.elderId)return;detail.value=data;for(const key of Object.keys(form))form[key]=data.profile[key]||''}
  catch(e){error.value=e?.response?.data?.message||'档案读取失败，请重试'}finally{loading.value=false}}
async function save(){if(saving.value)return;saving.value=true;try{await updateElder(route.params.elderId,{...form,birthDate:form.birthDate||undefined});ElMessage.success('档案已保存');await loadHeader()}catch(e){ElMessage.error(e?.response?.data?.message||'保存失败，请重试')}finally{saving.value=false}}
async function loadRelated(next=page.value,recover=true){if(active.value==='basic')return;const tab=active.value,id=route.params.elderId;relatedLoading.value=true;relatedError.value=''
  const endpoints={health:'/health-records/paged',alerts:'/alert-events/paged',orders:'/service-orders/admin/all',devices:'/devices/paged'}
  try{const data=(await request.get(endpoints[tab],{params:{elderId:id,scope:'ALL',pageNum:Number.isSafeInteger(next)&&next>0?next:page.value,pageSize:size.value}})).data;if(tab!==active.value||id!==route.params.elderId)return;rows.value=data.records;page.value=data.pageNum;total.value=data.total;const last=Math.max(1,Math.ceil(total.value/size.value));if(recover&&!rows.value.length&&page.value>last)return await loadRelated(last,false)}
  catch(e){relatedError.value=e?.response?.data?.message||'关联记录读取失败，请重试'}finally{relatedLoading.value=false}}
function changeSize(value){size.value=value;return loadRelated(1)}
function openDetail(row){if(active.value==='alerts')router.push(`/alerts/${row.id}`);else{orderId.value=row.id;orderVisible.value=true}}
watch(()=>route.params.elderId,loadHeader,{immediate:true})
</script>
<style scoped>.detail-panel{padding:24px}.archive-form{max-width:640px;margin-top:25px}.archive-identifiers,.related-heading{display:flex;gap:24px;align-items:center;flex-wrap:wrap;color:var(--care-muted);font-size:13px;margin:12px 0 24px}.archive-identifiers a{color:var(--care-brand)}.related-heading{justify-content:space-between}.archive-form .el-select{width:220px}</style>
