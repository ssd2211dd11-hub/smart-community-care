<template>
  <div class="profile-page">
    <div class="care-page-heading">
      <div><span class="care-eyebrow">我的照护</span><h1>个人中心</h1><p>维护自己的资料与登录安全。角色与账号状态由社区管理。</p></div>
      <el-button :disabled="loading || !user" @click="passwordOpen = true"><CareIcon name="shield" />修改密码</el-button>
    </div>
    <div class="profile-layout">
      <aside class="profile-aside">
        <section class="care-panel profile-summary" v-loading="loading" aria-label="账号概况">
          <div class="profile-avatar" aria-hidden="true">{{ (user?.realName || user?.username || '我').slice(0, 1) }}</div>
          <h2>{{ user?.realName || '—' }}</h2><p>{{ user?.username || '—' }}</p>
          <el-tag type="primary">{{ roleLabel }}</el-tag>
          <dl>
            <div><dt>账号状态</dt><dd><el-tag v-if="user" :type="user.enabled ? 'success' : 'info'" size="small">{{ user.enabled ? '启用中' : '已停用' }}</el-tag><span v-else>—</span></dd></div>
            <div><dt>联系方式</dt><dd>{{ user?.phone || '未填写' }}</dd></div>
          </dl>
        </section>
        <section class="care-panel security-panel">
          <div class="care-panel-heading"><h2>登录安全</h2><CareIcon name="shield" /></div>
          <div class="care-panel-body"><p>定期更新密码。修改后需使用新密码重新登录。</p><el-button :disabled="loading || !user" @click="passwordOpen = true">修改密码</el-button></div>
        </section>
      </aside>
      <section class="care-panel profile-editor" v-loading="loading">
        <div class="care-panel-heading"><div><h2>个人资料</h2><p>仅维护当前登录账号</p></div><span class="care-secondary">姓名、联系方式可修改</span></div>
        <div class="care-panel-body">
          <el-form ref="infoFormRef" :model="infoForm" :rules="infoRules" label-position="top" :disabled="loading || savingProfile || !user">
            <div class="profile-form-grid">
              <el-form-item label="账号"><el-input :model-value="user?.username || '—'" readonly /></el-form-item>
              <el-form-item label="角色"><el-input :model-value="roleLabel" readonly><template #suffix><span class="readonly-caption">不可自行变更</span></template></el-input></el-form-item>
              <el-form-item label="姓名" prop="realName"><el-input v-model="infoForm.realName" maxlength="50" placeholder="请输入姓名" /></el-form-item>
              <el-form-item label="联系电话" prop="phone"><el-input v-model="infoForm.phone" maxlength="20" placeholder="请输入联系电话" /></el-form-item>
              <el-form-item label="邮箱" prop="email" class="span-two"><el-input v-model="infoForm.email" maxlength="100" placeholder="选填" /></el-form-item>
              <template v-if="isElder">
                <h3 class="profile-section span-two">我的档案与联系方式</h3>
                <el-form-item label="出生日期" prop="birthDate"><el-date-picker v-model="infoForm.birthDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" /></el-form-item>
                <el-form-item label="性别" prop="gender"><el-select v-model="infoForm.gender" style="width: 100%"><el-option label="男" value="male" /><el-option label="女" value="female" /></el-select></el-form-item>
                <el-form-item label="居住地址" prop="address" class="span-two"><el-input v-model="infoForm.address" maxlength="200" placeholder="请输入住址" /></el-form-item>
                <el-form-item label="紧急联系人" prop="emergencyContact"><el-input v-model="infoForm.emergencyContact" maxlength="50" placeholder="选填" /></el-form-item>
                <el-form-item label="联系人电话" prop="emergencyPhone"><el-input v-model="infoForm.emergencyPhone" maxlength="20" placeholder="选填" /></el-form-item>
              </template>
            </div>
            <p class="readonly-tip">账号、角色与账号状态由管理员维护，本人不可修改。</p>
            <div class="profile-form-footer"><p>修改资料不会改变已下单订单的联系人与地址快照。</p><div><el-button @click="fillInfoForm(user)">恢复当前资料</el-button><el-button type="primary" :loading="savingProfile" @click="handleSaveProfile">保存资料</el-button></div></div>
          </el-form>
        </div>
      </section>
    </div>
    <el-dialog v-model="passwordOpen" title="修改密码" width="520px" :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting" @closed="resetPasswordForm">
      <p class="password-intro">使用原密码确认身份，设置新的登录密码。</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" :disabled="submitting" @submit.prevent>
        <el-form-item label="原密码" prop="oldPassword"><el-input v-model="form.oldPassword" type="password" autocomplete="current-password" show-password /></el-form-item>
        <el-form-item label="新密码" prop="newPassword"><el-input v-model="form.newPassword" type="password" autocomplete="new-password" show-password /></el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword"><el-input v-model="form.confirmPassword" type="password" autocomplete="new-password" show-password /></el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false" title="修改后当前登录会失效，需要用新密码重新登录。" />
      <template #footer><el-button :disabled="submitting" @click="passwordOpen = false">暂不修改</el-button><el-button type="primary" :loading="submitting" @click="handleChange">确认修改</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import CareIcon from '@/components/CareIcon.vue'
import { ElMessage } from 'element-plus'
import { changePassword, fetchCurrentUser, updateMyProfile } from '@/api/auth'
import { clearLogin, ROLE_LABELS } from '@/utils/auth'

const router = useRouter()
const formRef = ref(null)
const infoFormRef = ref(null)
const loading = ref(false)
const submitting = ref(false)
const savingProfile = ref(false)
const passwordOpen = ref(false)
function resetPasswordForm() {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
}

// 刷新后从后端取权威信息，不用本地存储的副本充当事实
const user = ref(null)
const roleLabel = computed(() => ROLE_LABELS[user.value?.role] || '未识别角色')
const isElder = computed(() => user.value?.role === 'ELDER')

const infoForm = reactive({
  realName: '',
  phone: '',
  email: '',
  birthDate: '',
  gender: '',
  address: '',
  emergencyContact: '',
  emergencyPhone: ''
})

const infoRules = {
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码长度 6-64 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await fetchCurrentUser()
    user.value = res.data
    fillInfoForm(res.data)
  } catch (error) {
    if (error.response?.status === 401) {
      clearLogin()
      await router.replace('/login')
    }
  } finally {
    loading.value = false
  }
})

function fillInfoForm(u) {
  if (!u) return
  infoForm.realName = u.realName || ''
  infoForm.phone = u.phone || ''
  infoForm.email = u.email || ''
  // 老人档案字段随 /auth/me 一并返回（后端把两者合并成一个响应，
  // 避免同一份「我的资料」发两次请求、两次结果还不一致）
  infoForm.birthDate = u.birthDate || ''
  infoForm.gender = u.gender || ''
  infoForm.address = u.address || ''
  infoForm.emergencyContact = u.emergencyContact || ''
  infoForm.emergencyPhone = u.emergencyPhone || ''
}

async function handleSaveProfile() {
  const valid = await infoFormRef.value.validate().catch(() => false)
  if (!valid) return

  savingProfile.value = true
  // 只提交表单上的字段。role / enabled / userId 一个都不带——
  // 后端归属取会话，页面传了也没有意义。
  const payload = {
    realName: infoForm.realName,
    phone: infoForm.phone,
    email: infoForm.email
  }
  if (isElder.value) {
    payload.birthDate = infoForm.birthDate
    payload.gender = infoForm.gender
    payload.address = infoForm.address
    payload.emergencyContact = infoForm.emergencyContact
    payload.emergencyPhone = infoForm.emergencyPhone
  }

  try {
    await updateMyProfile(payload)
    ElMessage.success('资料已更新')
    // 重新读取而不是本地改：保存后的真实值以服务端为准
    const res = await fetchCurrentUser()
    user.value = res.data
    fillInfoForm(res.data)
  } catch (error) {
    // 失败时保留表单内容，不清空——用户不该因为一次失败重填
  } finally {
    savingProfile.value = false
  }
}

async function handleChange() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    ElMessage.success('密码已修改，请重新登录')
    clearLogin()
    await router.replace('/login')
  } catch (error) {
    // 错误提示由拦截器统一处理；失败保留输入让人能改
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.profile-layout { display: grid; grid-template-columns: 300px minmax(0, 1fr); gap: 24px; align-items: start; }
.profile-summary { padding: 27px; text-align: center; }
.profile-avatar { width: 66px; height: 66px; border-radius: 50%; display: grid; place-items: center; background: var(--care-brand-soft); color: var(--care-brand); font-size: 27px; margin: 0 auto 16px; }
.profile-summary > p { margin: 5px 0 13px; color: var(--care-muted); font-size: 13px; overflow-wrap: anywhere; }
.profile-summary dl { border-top: 1px solid var(--care-line-soft); padding-top: 20px; margin: 24px 0 0; font-size: 13px; text-align: left; }
.profile-summary dl > div { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin: 12px 0; }
.profile-summary dt { color: var(--care-muted); }.profile-summary dd { margin: 0; }
.security-panel { margin-top: 20px; }.security-panel .care-panel-heading { padding: 19px 23px; }
.security-panel p { font-size: 13px; color: var(--care-muted); margin-bottom: 20px; line-height: 1.85; }
.profile-editor .care-panel-heading p { color: var(--care-brand); font-size: 12px; margin-top: 5px; }
.profile-editor .care-panel-heading > span { font-size: 12px; }
.profile-form-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 0 22px; }
.span-two { grid-column: 1 / -1; }
.profile-section { border-top: 1px solid var(--care-line-soft); margin: 3px 0 22px; padding-top: 22px; }
.readonly-caption { font-size: 12px; color: var(--care-muted); }
:deep(.el-input:has(input[readonly]) .el-input__wrapper) { background: #f5f4f2; }
.readonly-tip { color: var(--care-muted); font-size: 12px; }
.profile-form-footer { display: flex; justify-content: space-between; align-items: center; gap: 24px; border-top: 1px solid var(--care-line-soft); padding-top: 22px; margin-top: 21px; }
.profile-form-footer > p { color: var(--care-muted); font-size: 12px; max-width: 260px; }
.profile-form-footer > div { display: flex; flex-shrink: 0; gap: 10px; }.profile-form-footer .el-button + .el-button { margin-left: 0; }
.password-intro { color: var(--care-muted); margin-bottom: 22px; font-size: 14px; }
@media (min-width: 1650px) { .profile-layout { grid-template-columns: 330px minmax(0, 1fr); } .profile-summary { padding: 32px; } }
</style>
