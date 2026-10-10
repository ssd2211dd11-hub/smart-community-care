<template>
  <div>
    <div class="care-page-heading"><div><span class="care-eyebrow">社区管理</span><h1>账号管理</h1><p>维护居民与服务人员账号，停用保留已有业务记录。</p></div><el-button type="primary" @click="openCreate">创建账号</el-button></div>
    <section class="care-panel management-panel" v-loading="loading">
      <el-form inline @submit.prevent="load(1)">
        <el-form-item label="账号 / 姓名"><el-input v-model="filters.keyword" clearable placeholder="输入关键词" /></el-form-item>
        <el-form-item label="角色"><el-select v-model="filters.role" clearable style="width:160px"><el-option v-for="(label, role) in ROLE_LABELS" :key="role" :label="label" :value="role" /></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="filters.enabled" clearable style="width:130px"><el-option label="启用" :value="true"/><el-option label="停用" :value="false"/></el-select></el-form-item>
        <el-button type="primary" native-type="submit" :disabled="loading">查询</el-button><el-button @click="load()" :disabled="loading">刷新</el-button>
      </el-form>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false"><el-button link @click="load()">重试</el-button></el-alert>
      <el-table v-else :data="rows" empty-text="没有符合条件的账号">
        <el-table-column prop="username" label="账号" min-width="165" show-overflow-tooltip/><el-table-column prop="realName" label="姓名" min-width="115" show-overflow-tooltip/>
        <el-table-column label="角色" width="130"><template #default="{row}">{{ ROLE_LABELS[row.role] }}</template></el-table-column>
        <el-table-column prop="phone" label="电话" min-width="130"/><el-table-column prop="email" label="邮箱" min-width="160" show-overflow-tooltip/>
        <el-table-column label="状态" width="85"><template #default="{row}"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="160"><template #default="{row}"><el-button link @click="edit(row)">维护</el-button><el-button link :disabled="saving" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template></el-table-column>
      </el-table>
      <ListPagination :page="page" :size="size" :total="total" :loading="loading" @page="load" @size="changeSize"/>
    </section>
    <el-dialog v-model="dialog" :title="editing ? '维护账号资料' : '创建账号'" width="520px" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" @closed="form.password=''">
      <el-form ref="formRef" :model="form" label-width="90px" :disabled="saving" :rules="rules">
        <el-form-item label="角色" prop="role"><el-select v-model="form.role" :disabled="!!editing"><el-option label="老人" value="ELDER"/><el-option label="服务人员" value="SERVICE_STAFF"/><el-option v-if="editing && form.role==='COMMUNITY_ADMIN'" label="社区管理员" value="COMMUNITY_ADMIN"/></el-select></el-form-item>
        <el-form-item label="账号" prop="username"><el-input v-model="form.username" :disabled="!!editing" maxlength="50" /></el-form-item>
        <el-form-item v-if="!editing" label="初始密码" prop="password"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" maxlength="64"/></el-form-item>
        <el-form-item label="姓名" prop="realName"><el-input v-model="form.realName" maxlength="50"/></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="form.phone" maxlength="20"/></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" maxlength="100"/></el-form-item>
      </el-form>
      <p v-if="!editing && form.role==='ELDER'" class="care-secondary">创建后同时建立老人档案，可在老人档案中补齐联系与照护资料。</p>
      <template #footer><el-button :disabled="saving" @click="dialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ editing ? '保存' : '创建' }}</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ROLE_LABELS } from '@/utils/auth'
import ListPagination from '@/components/ListPagination.vue'
import { listAccounts, createAccount, updateAccount, setAccountEnabled } from '@/api/management'
const route=useRoute(), rows=ref([]), total=ref(0), page=ref(1), size=ref(20), loading=ref(false), error=ref('')
const filters=reactive({ keyword:typeof route.query.keyword==='string'?route.query.keyword:'', role:'', enabled:'' }), dialog=ref(false), editing=ref(null), saving=ref(false), formRef=ref()
const form=reactive({ username:'',password:'',realName:'',phone:'',email:'',role:'ELDER' })
const rules={ username:[{required:true,message:'填写账号',trigger:'blur'},{pattern:/^[A-Za-z0-9_]{3,50}$/,message:'使用3–50位字母、数字或下划线',trigger:'blur'}], password:[{required:true,min:6,max:64,message:'密码为6–64位',trigger:'blur'}],realName:[{required:true,whitespace:true,message:'填写姓名',trigger:'blur'}],role:[{required:true,message:'选择角色'}] }
async function load(next=page.value, recover=true) {
  loading.value=true; error.value=''; const requested=Number.isSafeInteger(next)&&next>0?next:page.value
  try { const result=(await listAccounts({...filters,enabled:filters.enabled===''?undefined:filters.enabled,pageNum:requested,pageSize:size.value})).data
    rows.value=result.records;total.value=result.total;page.value=result.pageNum
    const last=Math.max(1,Math.ceil(total.value/size.value));if(recover && !rows.value.length && page.value>last) return await load(last,false)
  } catch(e){error.value=e?.response?.data?.message||'账号读取失败，请重试'} finally{loading.value=false}
}
function changeSize(value){size.value=value;return load(1)}
function openCreate(){editing.value=null;Object.assign(form,{username:'',password:'',realName:'',phone:'',email:'',role:route.query.role==='SERVICE_STAFF'?'SERVICE_STAFF':'ELDER'});dialog.value=true;formRef.value?.clearValidate()}
function edit(row){editing.value=row.id;Object.assign(form,{...row,password:'',phone:row.phone||'',email:row.email||''});dialog.value=true;formRef.value?.clearValidate()}
async function save(){if(saving.value)return;if(!await formRef.value.validate().catch(()=>false))return;saving.value=true
  try {if(editing.value)await updateAccount(editing.value,{realName:form.realName,phone:form.phone,email:form.email});else await createAccount({...form})
    form.password='';dialog.value=false;ElMessage.success(editing.value?'资料已保存':'账号已创建');await load(1)
  } catch(e){ElMessage.error(e?.response?.data?.message||'结果未确认，请先查询账号后再操作')}finally{saving.value=false}}
async function toggle(row){if(saving.value)return
  try {await ElMessageBox.confirm(row.enabled?'停用后该账号所有会话将失效，已有业务记录保留。':'启用后该账号可以重新登录。',`${row.enabled?'停用':'启用'} ${row.realName}`,{type:'warning'});saving.value=true;await setAccountEnabled(row.id,!row.enabled);ElMessage.success('账号状态已更新');await load()}
  catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e?.response?.data?.message||'更新失败，请刷新确认')}finally{saving.value=false}}
onMounted(()=>{load();if(route.query.create==='1')openCreate()})
</script>
<style scoped>.management-panel { padding:24px; }.el-form--inline { display:flex; flex-wrap:wrap; align-items:baseline; gap:8px; }.el-form--inline .el-form-item{margin-right:12px;}</style>
