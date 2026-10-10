<template>
  <div class="reviews-page">
    <p class="muted">这里展示老人对您实际完成的订单给出的评分与留言；每单一次，服务人员不能代写或修改。</p>
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="card-title">收到的评价</span>
          <el-button link :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <!-- 读取失败与「还没有评价」是两回事，必须分开显示。
           失败时若展示空态，用户会以为没人评价过自己。 -->
      <el-alert
        v-if="error"
        type="error"
        :closable="false"
        show-icon
        title="评价读取失败"
        :description="error"
        class="load-error"
      >
        <template #default>
          <el-button link type="primary" @click="load">重试</el-button>
        </template>
      </el-alert>

      <template v-else>
        <div class="summary">
          <span class="summary-label">已评价 {{ ratedCount }} 单</span>
          <span v-if="ratedCount > 0" class="summary-avg">
            平均分 {{ averageRating.toFixed(1) }}
          </span>
          <span v-else class="summary-empty">暂无评价</span>
        </div>

        <el-empty v-if="!loading && reviews.length === 0" description="还没有收到评价" />

        <el-table v-else :data="reviews" v-loading="loading" size="small" row-key="id">
          <el-table-column label="评分" width="90">
            <template #default="{ row }">
              <el-rate :model-value="row.rating" disabled size="small" />
            </template>
          </el-table-column>
          <el-table-column label="反馈内容" min-width="220">
            <template #default="{ row }">
              <!-- 文本按原文展示，不做富文本渲染：评价是外部输入，
                   渲染成 HTML 会引入脚本注入面 -->
              <span class="review-text">{{ row.content || '（未填写文字）' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="评价时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>

        <p class="readonly-tip">
          评价由老人提交，本人只能查看，不能修改或删除。
        </p>
        <ListPagination :page="page" :size="size" :total="ratedCount" :loading="loading" @page="load" @size="changeSize"/>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import ListPagination from '@/components/ListPagination.vue'

const reviews = ref([])
const loading = ref(false)
const error = ref('')
const page=ref(1),size=ref(20),ratedCount=ref(0),averageRating=ref(null)

/**
 * 分页与汇总由后端在本人范围内计算；未评价不计入均分。
 */
async function load(next=page.value,recover=true) {
  loading.value = true
  error.value = ''
  try {
    const res=await request.get('/service-orders/reviews/received/paged',{params:{pageNum:Number.isSafeInteger(next)&&next>0?next:page.value,pageSize:size.value}})
    reviews.value=res.data.records;page.value=res.data.pageNum;ratedCount.value=res.data.ratedCount;averageRating.value=res.data.averageRating??null
    const last=Math.max(1,Math.ceil(ratedCount.value/size.value));if(recover&&!reviews.value.length&&page.value>last)return await load(last,false)
  } catch (e) {
    // 失败不塞空数组：空数组会被渲染成「还没有评价」，
    // 把读取失败伪装成业务空态
    reviews.value = []
    error.value = e?.response?.data?.message || '请稍后重试'
    ElMessage.error('评价加载失败')
  } finally {
    loading.value = false
  }
}
function changeSize(value){size.value=value;return load(1)}

function formatTime(v) {
  return v ? v.replace('T', ' ').slice(0, 16) : ''
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 16px;
  font-weight: 500;
}

.summary {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}

.summary-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.summary-avg {
  font-size: 15px;
  font-weight: 600;
  color: #88543e;
}

.summary-empty {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.review-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.readonly-tip {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.load-error {
  margin-bottom: 12px;
}
</style>
