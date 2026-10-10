<template>
  <div><div class="care-page-heading"><div><span class="care-eyebrow">居民照护</span><h1>老人档案</h1><p>从一份档案查看居民资料与关联业务。</p></div><el-button type="primary" @click="$router.push('/admin/users?create=1&role=ELDER')">创建老人账号</el-button></div>
    <section class="care-panel archive-panel" v-loading="loading">
      <el-form inline @submit.prevent="load(1)"><el-form-item label="姓名 / 账号 / 电话"><el-input v-model="keyword" clearable placeholder="输入关键词"/></el-form-item><el-form-item label="账号状态"><el-select v-model="enabled" clearable style="width:130px"><el-option label="启用" :value="true"/><el-option label="停用" :value="false"/></el-select></el-form-item><el-button type="primary" native-type="submit" :disabled="loading">查询</el-button><el-button @click="load()" :disabled="loading">刷新</el-button></el-form>
      <el-alert v-if="error" :title="error" type="error" :closable="false"><el-button link @click="load()">重试</el-button></el-alert>
      <el-table v-else :data="rows" empty-text="没有符合条件的老人档案"><el-table-column prop="realName" label="老人" min-width="120"/><el-table-column prop="username" label="账号" min-width="160" show-overflow-tooltip/><el-table-column prop="phone" label="电话" min-width="140"/><el-table-column prop="address" label="住址" min-width="170" show-overflow-tooltip/><el-table-column prop="emergencyContact" label="紧急联系人" min-width="120"/><el-table-column label="状态" width="90"><template #default="{row}"><el-tag :type="row.enabled?'success':'info'">{{row.enabled?'启用':'停用'}}</el-tag></template></el-table-column><el-table-column label="操作" width="100"><template #default="{row}"><el-button link @click="$router.push(`/admin/elders/${row.id}`)">查看档案</el-button></template></el-table-column></el-table>
      <ListPagination :page="page" :size="size" :total="total" :loading="loading" @page="load" @size="changeSize"/>
    </section>
  </div>
</template>
<script setup>
import { ref,onMounted } from 'vue'
import { listElders } from '@/api/management'
import ListPagination from '@/components/ListPagination.vue'
const rows=ref([]),page=ref(1),size=ref(20),total=ref(0),keyword=ref(''),enabled=ref(''),loading=ref(false),error=ref('')
async function load(next=page.value,recover=true){loading.value=true;error.value='';const requested=Number.isSafeInteger(next)&&next>0?next:page.value
  try{const data=(await listElders({keyword:keyword.value,enabled:enabled.value===''?undefined:enabled.value,pageNum:requested,pageSize:size.value})).data
    rows.value=data.records;total.value=data.total;page.value=data.pageNum;const last=Math.max(1,Math.ceil(total.value/size.value));if(recover&&!rows.value.length&&page.value>last)return await load(last,false)
  }catch(e){error.value=e?.response?.data?.message||'档案读取失败，请重试'}finally{loading.value=false}}
function changeSize(value){size.value=value;return load(1)}
onMounted(load)
</script>
<style scoped>.archive-panel{padding:24px}.el-form--inline{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px}</style>
