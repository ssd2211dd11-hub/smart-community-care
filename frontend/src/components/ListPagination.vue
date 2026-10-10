<template>
  <el-pagination :current-page="page" :page-size="size" :total="total" :disabled="loading"
    :page-sizes="[10,20,50,100]" layout="total, sizes, prev, pager, next, jumper"
    @current-change="$emit('page', $event)" @size-change="$emit('size', $event)" />
</template>
<script setup>
defineProps({ page: { type: Number, default: 1 }, size: { type: Number, default: 20 }, total: { type: Number, default: 0 }, loading: Boolean })
defineEmits(['page', 'size'])
</script>
<style scoped>.el-pagination { margin-top: 24px; flex-wrap: wrap; gap: 8px; }</style>
