<template>
  <BaseLayout title="老人端" :menus="menus" />
</template>

<script setup>
import BaseLayout from './BaseLayout.vue'

const menus = [
  { path: '/elder', label: '首页' },
  { path: '/elder/health-input', label: '健康记录' },
  { path: '/elder/health-summary', label: '七日概览' },
  { path: '/elder/my-alerts', label: '我的事项' },
  { path: '/elder/help-call', label: '提交求助' },
  { path: '/elder/service-booking', label: '服务预约' },
  { path: '/elder/my-orders', label: '我的订单' },
  { path: '/elder/my-devices', label: '我的设备' }
]
</script>
