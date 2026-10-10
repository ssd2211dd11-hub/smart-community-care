<template>
  <div class="login-page">
    <section class="login-scene">
      <router-link to="/login" class="login-brand"><CareIcon name="brand" /><span><strong>社区照护</strong><small>COMMUNITY CARE</small></span></router-link>
      <div class="scene-copy"><span class="care-eyebrow">让照护走进日常</span><h1>熟悉的社区，<br />安心的每一天。</h1><p>连接居民、社区与服务人员，<br />让每一项需求、每一次服务都有清楚的回应。</p></div>
      <img class="community-scene" :src="communityScene" alt="社区街道、住宅与散步的居民" />
      <div class="scene-footer"><span>社区居家养老服务与告警协同平台</span><span>以人为本 · 照护有序</span></div>
    </section>
    <section class="login-form-side">
      <div class="login-form-wrap"><span class="care-eyebrow">欢迎回来</span><h2>登录社区照护</h2><p class="login-intro">使用您的账号，进入自己的照护与服务工作区。</p>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" @submit.prevent>
          <el-form-item label="用户名" prop="username"><el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" clearable /></el-form-item>
          <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" placeholder="请输入密码" autocomplete="current-password" show-password @keyup.enter="handleLogin" /></el-form-item>
          <el-button type="primary" class="login-button" :loading="loading" @click="handleLogin">登录</el-button>
        </el-form>
        <div class="login-footer"><span>第一次使用？</span><router-link to="/register">注册老人账号<CareIcon name="arrow" /></router-link></div>
        <p class="account-note"><CareIcon name="shield" /><span>管理员和服务人员使用社区分配的账号登录。</span></p>
      </div>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import CareIcon from '@/components/CareIcon.vue'
import communityScene from '@/assets/community-scene.svg'
import { ElMessage } from 'element-plus'
import { login } from '@/api/auth'
import { saveLogin, homePathFor } from '@/utils/auth'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const res = await login(form)
    // 后端返回的 role 才是事实；此处只保存，不据此做权限判断
    const { token, userId, username, realName, role } = res.data
    saveLogin({
      token,
      user: { userId, username, realName, role }
    })
    ElMessage.success('登录成功')
    await router.replace(homePathFor(role))
  } catch (error) {
    // 错误提示已由请求拦截器统一处理，这里不重复弹
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: grid; grid-template-columns: 56% 44%; }
.login-scene { position: relative; display: flex; flex-direction: column; padding: 42px 7%; background: #eeece6; border-right: 1px solid var(--care-line); }
.login-brand { display: flex; align-items: center; gap: 12px; width: fit-content; }.login-brand .care-icon { width: 36px; height: 36px; }.login-brand strong { display: block; font-size: 20px; letter-spacing: .1em; }.login-brand small { display: block; font-size: 9px; letter-spacing: .13em; color: var(--care-muted); }
.scene-copy { margin: auto 0 16px; padding-top: 70px; }.scene-copy h1 { font-size: clamp(36px, 3.3vw, 54px); line-height: 1.5; font-weight: 500; letter-spacing: .04em; }.scene-copy p { color: var(--care-muted); font-size: 15px; margin-top: 23px; line-height: 1.9; }
.community-scene { width: 100%; max-height: 320px; object-fit: contain; margin: 10px 0 30px; }
.scene-footer { border-top: 1px solid #d6d3ca; padding-top: 19px; display: flex; justify-content: space-between; gap: 20px; color: var(--care-muted); font-size: 11px; }
.login-form-side { display: grid; place-items: center; padding: 60px; background: #fff; }.login-form-wrap { width: 100%; max-width: 400px; }.login-form-wrap h2 { font-size: 28px; margin-bottom: 11px; }.login-intro { color: var(--care-muted); font-size: 14px; margin-bottom: 36px; }
.login-button { width: 100%; margin-top: 6px; }.login-footer { margin-top: 24px; display: flex; justify-content: center; gap: 10px; font-size: 14px; color: var(--care-muted); }.login-footer a { display: inline-flex; gap: 6px; align-items: center; }.login-footer .care-icon { width: 16px; height: 16px; }
.account-note { display: flex; align-items: center; gap: 9px; font-size: 12px; color: var(--care-muted); margin-top: 38px; border-top: 1px solid var(--care-line-soft); padding-top: 23px; }.account-note .care-icon { width: 17px; height: 17px; }
@media (max-height: 850px) { .login-scene { padding-top: 32px; padding-bottom: 28px; } .scene-copy { padding-top: 28px; } .scene-copy h1 { font-size: 40px; } .community-scene { max-height: 245px; margin-bottom: 20px; } .login-form-side { padding-top: 36px; padding-bottom: 36px; } }
</style>
