<template>
  <div class="message-page">
    <el-result icon="warning" title="403无访问权限" sub-title="当前角色无权访问该页面">
      <template #extra>
        <el-button type="primary" @click="$router.replace('/')">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.message-page { display:flex; align-items:center; justify-content:center; min-height:100vh; background:#f5f7fa; }
</style>
