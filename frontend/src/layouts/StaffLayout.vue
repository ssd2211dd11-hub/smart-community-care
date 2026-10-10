<template>
  <BaseLayout title="服务人员端" :menus="menus" />
</template>

<script setup>
import BaseLayout from './BaseLayout.vue'

const menus = [
  { path: '/staff', label: '我的工作台' },
  { path: '/staff/alerts', label: '事件任务' },
  { path: '/staff/tasks', label: '服务任务' },
  { path: '/staff/reviews', label: '收到的评价' }
]
</script>
