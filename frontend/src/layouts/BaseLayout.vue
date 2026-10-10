<template>
  <div class="layout" :class="{ 'elder-layout': user?.role === 'ELDER' }">
    <aside class="sidebar">
      <router-link :to="menus[0]?.path || '/'" class="brand" aria-label="社区照护首页">
        <CareIcon name="brand" />
        <span><strong>社区照护</strong><small>COMMUNITY CARE</small></span>
      </router-link>
      <p class="nav-label">{{ title }}</p>
      <nav class="menu" aria-label="业务导航">
        <router-link v-for="item in menus" :key="item.path" :to="item.path" class="nav-link" :class="{ active: isCurrent(item.path) }" :aria-current="isCurrent(item.path) ? 'page' : undefined">
          <CareIcon :name="menuIcon(item.path)" /><span>{{ item.label }}</span>
        </router-link>
      </nav>
      <div class="sidebar-note"><CareIcon name="location" /><span><strong>照护有序，生活安心</strong><small>把每一次服务放在心上</small></span></div>
    </aside>
    <div class="main">
      <header class="topbar">
        <div class="context"><span>{{ roleLabel }}</span><i aria-hidden="true"></i><span>{{ currentPage }}</span></div>
        <div class="top-right">
          <span class="environment">社区服务工作区</span>
          <button type="button" class="avatar-entry" :aria-label="'进入个人中心（' + (user?.realName || '未命名') + '）'" title="个人中心" @click="goProfile">
            <span class="avatar" aria-hidden="true">{{ avatarInitial }}</span>
            <span class="user-name">{{ user?.realName || user?.username }}<small>{{ roleLabel }}</small></span>
          </button>
          <el-button link type="primary" @click="handleLogout">退出</el-button>
        </div>
      </header>
      <main class="content"><div v-if="pageDescription" class="care-page-heading"><div><span class="care-eyebrow">{{ roleLabel }}</span><h1>{{ currentPage }}</h1><p>{{ pageDescription }}</p></div></div><router-view /></main>
      <footer class="workspace-footer"><span>社区照护</span><span>社区居家养老服务与告警协同平台</span></footer>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CareIcon from '@/components/CareIcon.vue'
import { ElMessage } from 'element-plus'
import { logout, fetchCurrentUser } from '@/api/auth'
import { clearLogin, getStoredUser, ROLE_LABELS } from '@/utils/auth'

const props = defineProps({
  title: { type: String, required: true },
  menus: { type: Array, required: true }
})

const router = useRouter()
const route = useRoute()
function isCurrent(path) { return route.path === path || path === '/admin/elders' && route.path.startsWith(path + '/') }
const currentPage = computed(() => route.path.endsWith('/profile') ? '个人中心' : route.path.startsWith('/admin/elders/') ? '老人档案详情' : props.menus.find(item => item.path === route.path)?.label || props.title)
const descriptions = {
  '/admin/services': '维护服务项目、开放时段与预约名额。',
  '/admin/orders': '查看预约记录，安排责任人与跟进服务进度。',
  '/admin/alerts': '跟进社区事项的分派、响应与处理。',
  '/admin/devices': '查看设备与现有模拟功能的状态。',
  '/elder/health-summary': '查看最近七个自然日的健康记录。',
  '/elder/my-alerts': '查看自己提交或关联的社区事项。',
  '/elder/help-call': '填写服务需求并查看后续处理情况。',
  '/elder/service-booking': '选择需要的服务与时段，确认预约信息。',
  '/elder/my-orders': '查看自己的预约、服务进度与评价。',
  '/elder/my-devices': '查看本人设备状态与现有模拟控制。',
  '/staff/tasks': '查看当前分派给你的服务与本人参与记录。',
  '/staff/alerts': '跟进分派给你的事项，及时响应和处理。',
  '/staff/reviews': '查看服务收到的评价与居民反馈。'
}
const pageDescription = computed(() => descriptions[route.path] || '')
function menuIcon(path) {
  if (path.endsWith('/health-input')) return 'heart'
  if (path.endsWith('/health-summary')) return 'chart'
  if (path.includes('devices')) return 'device'
  if (path.includes('help-call')) return 'alert'
  if (path.includes('alerts')) return 'alert'
  if (path.includes('service-booking') || path.endsWith('/services')) return 'calendar'
  if (path.includes('orders') || path.endsWith('/tasks')) return 'order'
  if (path.endsWith('/reviews')) return 'star'
  return path === '/elder' ? 'home' : 'grid'
}

const user = computed(() => getStoredUser())
const roleLabel = computed(() => ROLE_LABELS[user.value?.role] || '未识别角色')

/**
 * 头像里的首字。
 *
 * <p>没有真实头像图片时用它占位；取第一个字符，中英文都适用。
 * 纯展示，不参与任何权限判断。
 */
const avatarInitial = computed(() => {
  const name = user.value?.realName || user.value?.username || ''
  return name ? name.trim().charAt(0).toUpperCase() : '?'
})

/**
 * 按会话角色进入对应的个人中心路由，三个角色共用页面组件。
 */
const PROFILE_PATH_BY_ROLE = {
  COMMUNITY_ADMIN: '/admin/profile',
  ELDER: '/elder/profile',
  SERVICE_STAFF: '/staff/profile'
}

function goProfile() {
  const target = PROFILE_PATH_BY_ROLE[user.value?.role]
  if (!target) {
    ElMessage.warning('当前账号角色未识别，无法进入个人中心')
    return
  }
  router.push(target)
}

// 刷新后向后端确认身份：本地存的 role 只是展示用，不是权限事实
onMounted(async () => {
  try {
    await fetchCurrentUser()
  } catch (error) {
    if (error.response?.status === 401) {
      clearLogin()
      await router.replace('/login')
    }
  }
})

async function handleLogout() {
  try {
    await logout()
  } catch (error) {
    // 令牌可能已失效，退出本地状态即可
  } finally {
    clearLogin()
    ElMessage.success('已退出登录')
    await router.replace('/login')
  }
}
</script>

<style scoped>
.layout { display: grid; grid-template-columns: 208px minmax(0, 1fr); min-height: 100vh; }
.sidebar { height: 100vh; position: sticky; top: 0; display: flex; flex-direction: column; padding: 27px 17px 20px; background: #fcfcfb; border-right: 1px solid var(--care-line); }
.brand { display: flex; gap: 11px; align-items: center; padding: 0 9px 29px; }
.brand > .care-icon { width: 35px; height: 35px; color: var(--care-brand); }
.brand strong { display: block; font-size: 19px; font-weight: 600; letter-spacing: .08em; }
.brand small { display: block; font-size: 9px; letter-spacing: .12em; color: var(--care-muted); white-space: nowrap; margin-top: 2px; }
.nav-label { font-size: 11px; color: var(--care-muted); margin: 8px 14px 12px; }
.menu { display: flex; flex-direction: column; gap: 4px; }
.nav-link { position: relative; display: flex; gap: 12px; align-items: center; padding: 11px 14px; border-radius: 7px; color: #686561; font-size: 14px; }
.nav-link .care-icon { width: 19px; height: 19px; color: #7d7975; }
.nav-link:hover { background: #f3f2ef; }
.nav-link.active { background: var(--care-brand-soft); color: var(--care-brand); font-weight: 600; }
.nav-link.active .care-icon { color: var(--care-brand); }
.nav-link.active::before { position: absolute; content: ''; width: 3px; left: -17px; top: 12px; bottom: 12px; background: var(--care-brand); border-radius: 0 3px 3px 0; }
.sidebar-note { margin-top: auto; border-top: 1px solid var(--care-line); padding: 20px 8px 0; display: flex; gap: 9px; color: var(--care-muted); font-size: 12px; }
.sidebar-note > .care-icon { width: 18px; height: 18px; }
.sidebar-note strong { display: block; font-weight: 500; }
.sidebar-note small { display: block; font-size: 11px; margin-top: 3px; }
.main { min-width: 0; display: flex; flex-direction: column; }
.topbar { min-height: 76px; padding: 0 34px; display: flex; justify-content: space-between; align-items: center; gap: 24px; background: #fff; border-bottom: 1px solid var(--care-line); }
.context { display: flex; gap: 11px; align-items: center; font-size: 13px; color: var(--care-muted); }
.context i { height: 15px; width: 1px; background: var(--care-line); }
.top-right { display: flex; align-items: center; gap: 20px; }
.environment { font-size: 12px; color: var(--care-muted); }
.environment::before { content: '·'; margin-right: 5px; }
.avatar-entry { display: flex; align-items: center; gap: 10px; border: 0; border-left: 1px solid var(--care-line); padding: 0 0 0 20px; background: transparent; cursor: pointer; color: var(--care-ink); }
.avatar-entry:hover .avatar { background: var(--care-brand-soft); color: var(--care-brand); }
.avatar { display: grid; place-items: center; width: 33px; height: 33px; border-radius: 50%; background: #e7e5e3; color: #605a54; font-size: 13px; }
.user-name { font-size: 13px; text-align: left; max-width: 180px; overflow-wrap: anywhere; }
.user-name small { display: block; color: var(--care-muted); font-size: 11px; margin-top: 1px; }
.content { width: 100%; max-width: 1720px; margin: 0 auto; padding: 30px 34px 26px; flex: 1; min-width: 0; }
.workspace-footer { display: flex; justify-content: space-between; gap: 20px; margin: 0 34px; padding: 18px 0 22px; border-top: 1px solid var(--care-line); color: var(--care-muted); font-size: 11px; }
.elder-layout .nav-link { font-size: 15px; padding-top: 12px; padding-bottom: 12px; }
.elder-layout .content { font-size: 15px; }
@media (min-width: 1650px) { .layout { grid-template-columns: 224px minmax(0, 1fr); } .content { padding: 36px 44px 30px; } .topbar { padding: 0 44px; } .workspace-footer { margin: 0 44px; } }
</style>
