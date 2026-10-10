<template>
  <BaseLayout title="社区管理端" :menus="menus" />
</template>

<script setup>
import BaseLayout from './BaseLayout.vue'

const menus = [
  { path: '/admin', label: '工作台' },
  { path: '/admin/elders', label: '老人档案' },
  { path: '/admin/users', label: '账号管理' },
  { path: '/admin/alerts', label: '事件中心' },
  { path: '/admin/services', label: '服务与时段' },
  { path: '/admin/orders', label: '订单管理' },
  { path: '/admin/devices', label: '设备管理' }
]
</script>
