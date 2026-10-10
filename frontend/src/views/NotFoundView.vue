<template>
  <div class="message-page">
    <el-result icon="info" title="404 页面不存在" sub-title="请检查地址是否正确">
      <template #extra>
        <el-button type="primary" @click="$router.replace('/')">返回首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.message-page { display:flex; align-items:center; justify-content:center; min-height:100vh; background:#f5f7fa; }
</style>
