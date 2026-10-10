<template>
  <div class="workbench" v-loading="loading">
    <div class="care-page-heading"><div><span class="care-eyebrow">{{admin?'社区照护':'我的工作台'}}</span><h1>{{admin?'让今天的照护有序开展':'把每一件托付照顾好'}}</h1><p>{{user?.realName||user?.username}}，从待办开始查看今天的安排。</p></div><el-button @click="load" :disabled="loading">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="load">重试</el-button></el-alert>
    <template v-else-if="data">
      <div class="summary-band"><router-link v-for="card in cards" :key="card.key" :to="card.route" class="summary-entry"><strong>{{data.counts[card.key]}}</strong><span><b>{{card.label}}</b><small>{{card.hint}}</small></span><CareIcon :name="card.icon"/></router-link></div>
      <div class="workboard"><div>
        <section class="care-panel"><div class="care-panel-heading"><h2>{{admin?'未响应事项':'待接收事项'}}</h2><router-link :to="eventRoute">查看全部</router-link></div><div v-if="!data.pendingEvents.length" class="care-empty">当前没有这类待办</div><router-link v-for="event in data.pendingEvents" :key="event.id" :to="`/alerts/${event.id}`" class="business-row"><span class="event-mark"></span><span><b>{{event.title}}</b><small>{{event.eventNo}}</small></span><el-tag type="warning">{{event.status==='PENDING'?'待分派':'待接收'}}</el-tag></router-link><div class="care-panel-footer"><span>总数{{data.counts.pendingEvents}} · 最多显示5条</span></div></section>
        <section class="care-panel spacing-panel"><div class="care-panel-heading"><h2>{{admin?'待分派服务':'待接单服务'}}</h2><router-link :to="orderRoute('pending')">查看全部</router-link></div><div v-if="!data.pendingOrders.length" class="care-empty">当前没有这类待办</div><router-link v-for="order in data.pendingOrders" :key="order.id" :to="orderRoute('pending')" class="business-row"><CareIcon name="order"/><span><b>{{order.title}}</b><small>{{formatTime(order.startsAt)}} · {{order.contactName}}</small></span><el-tag type="warning">{{label(order.status)}}</el-tag></router-link><div class="care-panel-footer"><span>总数{{data.counts.pendingOrders}} · 最多显示5条</span></div></section>
        <section v-if="admin" class="care-panel spacing-panel"><div class="care-panel-heading"><h2>最近七日</h2><span class="care-secondary">新增事项 / 完成服务</span></div><div class="trend-grid"><div v-for="day in data.trend" :key="day.date" class="trend-day"><div class="trend-values"><span>{{day.newEvents}}</span><b>{{day.completedOrders}}</b></div><div class="trend-bars"><i :style="{height:barHeight(day.newEvents)}"></i><i class="done" :style="{height:barHeight(day.completedOrders)}"></i></div><small>{{day.date.slice(5)}}</small></div></div></section>
        <section v-else class="care-panel spacing-panel"><div class="care-panel-heading"><h2>处理中事项</h2><router-link to="/staff/alerts?status=PROCESSING">查看全部</router-link></div><div v-if="!data.processingEvents.length" class="care-empty">暂无正在处理的事项</div><router-link v-for="event in data.processingEvents" :key="event.id" :to="`/alerts/${event.id}`" class="business-row"><span><b>{{event.title}}</b><small>{{event.eventNo}}</small></span><el-tag>处理中</el-tag></router-link><div class="care-panel-footer"><span>共{{data.counts.processingEvents}}条</span></div></section>
      </div><div>
        <section class="care-panel"><div class="care-panel-heading"><h2>今天的服务</h2><span class="care-secondary">{{data.businessDate}}</span></div><div v-if="!data.todayOrders.length" class="care-empty">今天暂无服务安排</div><router-link v-for="order in data.todayOrders" :key="order.id" :to="orderRoute('today')" class="business-row"><time>{{String(order.startsAt).slice(11,16)}}</time><span><b>{{order.title}}</b><small>{{order.contactName}}</small></span><el-tag :type="order.status==='COMPLETED'?'success':'info'">{{label(order.status)}}</el-tag></router-link><div class="care-panel-footer"><router-link :to="orderRoute('today')">查看全部{{data.counts.todayOrders}}笔</router-link><span>最多显示8条</span></div></section>
        <section class="care-panel spacing-panel"><div class="care-panel-heading"><h2>近期完成</h2><CareIcon name="check"/></div><div class="care-panel-body"><p class="completed-count"><strong>{{data.counts.recentCompleted}}</strong><span>近七个自然日完成的服务</span></p><router-link :to="orderRoute('completed')" class="care-action-link">查看已完成服务<CareIcon name="arrow"/></router-link></div></section>
        <section v-if="admin" class="care-panel spacing-panel shortcuts"><h2>居民与服务支持</h2><router-link to="/admin/elders">老人档案<CareIcon name="arrow"/></router-link><router-link to="/admin/users">账号管理<CareIcon name="arrow"/></router-link><router-link to="/admin/devices">模拟设备<CareIcon name="arrow"/></router-link><router-link to="/admin/services">服务与时段<CareIcon name="arrow"/></router-link></section>
        <p class="data-note">{{data.businessZone}} · 更新于{{formatTime(data.dataAsOf)}}<br>计数覆盖全部可见记录，预览有条数上限。</p>
      </div></div>
    </template>
  </div>
</template>
<script setup>
import { ref,computed,onMounted } from 'vue'
import { getWorkbench } from '@/api/management'
import { getStoredUser } from '@/utils/auth'
import CareIcon from '@/components/CareIcon.vue'
const props=defineProps({admin:Boolean}),user=computed(getStoredUser),loading=ref(false),error=ref(''),data=ref(null)
const eventRoute=computed(()=>props.admin?'/admin/alerts?unresponded=true':'/staff/alerts?status=ASSIGNED')
const cards=computed(()=>[
  {key:'pendingEvents',label:props.admin?'未响应事项':'待接收事项',hint:props.admin?'等待首次响应':'分派给我的事项',route:eventRoute.value,icon:'alert'},
  {key:'pendingOrders',label:props.admin?'待分派服务':'待接单服务',hint:'安排每一笔服务',route:orderRoute('pending'),icon:'order'},
  {key:'todayOrders',label:'今日安排',hint:'按预约开始日期统计',route:orderRoute('today'),icon:'calendar'}])
function orderRoute(kind) {
  const path = props.admin ? '/admin/orders' : '/staff/tasks'
  const date = data.value?.businessDate
  if (kind === 'today') {
    return {path, query:{scope:'ALL',fromDate:date,toDate:date,excludeEnded:'true'}}
  }
  if (kind === 'completed') {
    const start = new Date(date + 'T00:00:00Z')
    start.setUTCDate(start.getUTCDate() - 6)
    return {path, query:{scope:'HISTORY',status:'COMPLETED',dateField:'COMPLETION',fromDate:start.toISOString().slice(0,10),toDate:date}}
  }
  return {path, query:{status:props.admin ? 'PENDING_ASSIGNMENT' : 'ASSIGNED'}}
}
function label(status){return {PENDING_ASSIGNMENT:'待分派',ASSIGNED:'待接单',ACCEPTED:'待服务',IN_SERVICE:'服务中',PENDING_CONFIRMATION:'待确认',COMPLETED:'已完成',CANCELLED:'已取消',TERMINATED:'已终止'}[status]||status}
function formatTime(value){return value?String(value).replace('T',' ').slice(0,16):'—'}
const peak=computed(()=>Math.max(1,...(data.value?.trend||[]).flatMap(day=>[day.newEvents,day.completedOrders])))
function barHeight(value){return (value/peak.value*72)+'px'}
async function load(){loading.value=true;error.value='';try{data.value=(await getWorkbench()).data}catch(e){error.value=e?.response?.data?.message||'工作台读取失败，请重试'}finally{loading.value=false}}
onMounted(load)
</script>
<style scoped>
.summary-band{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));background:white;border:1px solid var(--care-line);border-radius:10px;margin-bottom:24px;padding:22px 0}.summary-entry{display:flex;gap:22px;align-items:center;padding:0 25px;color:var(--care-ink);border-right:1px solid var(--care-line)}.summary-entry:last-child{border:0}.summary-entry strong{font:500 34px/1.3 'Segoe UI',sans-serif}.summary-entry b{display:block;font-weight:500}.summary-entry small{display:block;margin-top:5px;color:var(--care-muted);font-size:12px}.summary-entry>.care-icon{margin-left:auto;color:var(--care-brand)}
.workboard{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(0,1fr);gap:24px;align-items:start}.workboard>div{min-width:0}.spacing-panel{margin-top:24px}.business-row{display:flex;gap:14px;align-items:center;padding:20px 23px;border-bottom:1px solid var(--care-line-soft);color:var(--care-ink)}.business-row:hover{background:#faf8f5}.business-row b{display:block;font-size:14px;font-weight:500}.business-row small{display:block;color:var(--care-muted);font-size:12px;margin-top:6px}.business-row>.el-tag{margin-left:auto;flex-shrink:0}.business-row time,.business-row>.care-icon{color:var(--care-brand)}.event-mark{width:3px;height:35px;background:#b68a4b;border-radius:3px}.care-panel-heading>a{font-size:13px;color:var(--care-brand)}.completed-count{display:flex;align-items:baseline;gap:14px;margin-bottom:18px}.completed-count strong{font:500 34px 'Segoe UI',sans-serif}.completed-count span,.data-note{font-size:12px;color:var(--care-muted)}.data-note{margin-top:18px;line-height:1.9}.shortcuts{padding:24px}.shortcuts h2{margin-bottom:15px}.shortcuts>a{display:flex;justify-content:space-between;padding:13px 0;color:var(--care-ink);border-top:1px solid var(--care-line-soft)}.trend-grid{display:grid;grid-template-columns:repeat(7,1fr);padding:24px 20px;gap:10px}.trend-day{text-align:center}.trend-values{display:flex;justify-content:center;gap:10px;font-size:12px;color:#b49168}.trend-values b{color:var(--care-brand);font-weight:500}.trend-bars{display:flex;gap:4px;height:80px;align-items:end;justify-content:center;margin:8px 0}.trend-bars i{display:block;width:9px;background:#d4bd9d;border-radius:3px 3px 0 0}.trend-bars .done{background:var(--care-brand)}.trend-day small{font-size:12px;color:var(--care-muted)}
</style>
