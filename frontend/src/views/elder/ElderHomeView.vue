<template>
  <div class="elder-home">
    <section class="elder-welcome care-panel"><div><span class="care-eyebrow">我的照护</span><h1>{{ user?.realName || '您好' }}，欢迎回来</h1><p>预约需要的服务，记录日常健康，<br />让社区照护更贴近您的生活。</p><router-link to="/elder/service-booking" class="care-action-link">看看可以预约的服务<CareIcon name="arrow" /></router-link></div><img :src="communityScene" alt="社区住宅和散步的居民" /></section>
    <div class="elder-actions">
      <router-link to="/elder/service-booking" class="care-panel elder-action"><CareIcon name="calendar" /><h2>预约上门服务</h2><p>选择服务与合适的时间</p><span>去预约<CareIcon name="arrow" /></span></router-link>
      <router-link to="/elder/health-input" class="care-panel elder-action"><CareIcon name="heart" /><h2>记录今天的健康</h2><p>记录身体情况，查看日常变化</p><span>录入健康数据<CareIcon name="arrow" /></span></router-link>
      <router-link to="/elder/help-call" class="care-panel elder-action help-action"><CareIcon name="alert" /><h2>我需要帮助</h2><p>向社区提交生活服务需求</p><span>提交求助<CareIcon name="arrow" /></span></router-link>
    </div>
    <section class="care-panel elder-links"><h2>我的记录</h2><div><router-link to="/elder/health-summary"><CareIcon name="chart" />查看七日概览<CareIcon name="arrow" /></router-link><router-link to="/elder/my-alerts"><CareIcon name="alert" />我的事项<CareIcon name="arrow" /></router-link><router-link to="/elder/my-orders"><CareIcon name="order" />我的订单<CareIcon name="arrow" /></router-link></div></section>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import CareIcon from '@/components/CareIcon.vue'
import communityScene from '@/assets/community-scene.svg'
import { getStoredUser, ROLE_LABELS } from '@/utils/auth'

const user = computed(() => getStoredUser())
const roleLabel = computed(() => ROLE_LABELS[user.value?.role] || '未识别角色')
</script>

<style scoped>
.elder-welcome { display: flex; gap: 40px; align-items: center; justify-content: space-between; padding: 30px 34px; background: #f9f8f4; }.elder-welcome h1 { font-size: 28px; }.elder-welcome p { margin: 16px 0 21px; color: var(--care-muted); line-height: 1.85; }.elder-welcome img { width: 46%; height: 220px; object-fit: contain; }
.elder-actions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px; margin: 26px 0; }.elder-action { padding: 25px 27px; color: var(--care-ink); }.elder-action > .care-icon { width: 27px; height: 27px; color: var(--care-brand); margin-bottom: 19px; }.elder-action h2 { font-size: 20px; }.elder-action p { margin: 9px 0 22px; color: var(--care-muted); font-size: 14px; }.elder-action > span { display: flex; align-items: center; gap: 10px; color: var(--care-brand); font-size: 14px; }.elder-action > span .care-icon { width: 17px; height: 17px; }.elder-action:hover { border-color: var(--care-brand); }.help-action > .care-icon, .help-action > span { color: #b13c38; }
.elder-links { padding: 24px 27px; }.elder-links > div { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 24px; margin-top: 19px; }.elder-links a { display: flex; gap: 11px; align-items: center; font-size: 15px; }.elder-links a > .care-icon:last-child { width: 17px; margin-left: auto; }.elder-links a + a { border-left: 1px solid var(--care-line); padding-left: 23px; }
</style>
