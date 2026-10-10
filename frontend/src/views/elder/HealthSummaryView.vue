<template>
  <div class="health-summary" v-loading="loading">
    <el-card v-if="summary">
      <template #header>
        <div class="card-header">
          <span class="card-title">七日健康概览</span>
          <span class="as-of">数据截至 {{ summary.dataAsOf }}</span>
        </div>
      </template>

      <el-row :gutter="16">
        <el-col v-for="m in summary.metrics" :key="m.metric" :span="4">
          <el-card shadow="never" class="metric-card">
            <div class="metric-label">{{ m.label }}</div>
            <template v-if="m.sampleCount > 0">
              <div class="metric-value">
                {{ m.average }}<span class="metric-unit">{{ m.unit }}</span>
              </div>
              <div class="metric-detail">
                最低 {{ m.min }} / 最高 {{ m.max }}
              </div>
              <div class="metric-detail">样本 {{ m.sampleCount }} 条</div>
              <div v-if="m.hitCount > 0" class="metric-hit">
                规则命中 {{ m.hitCount }} 次
              </div>
            </template>
            <template v-else>
              <!-- 没有样本就明说没有，不用 0 冒充 -->
              <div class="metric-value muted">暂无数据</div>
              <div class="metric-detail">本周没有该项记录</div>
            </template>
          </el-card>
        </el-col>
      </el-row>
    </el-card>

    <el-card v-if="trendPoints.length" class="chart-card">
      <template #header><span class="card-title">七日趋势</span></template>

      <el-tabs v-model="activeMetric">
        <el-tab-pane
          v-for="m in metricsWithData"
          :key="m.metric"
          :label="`${m.label}（${m.unit}）`"
          :name="m.metric"
        >
          <DataTrendChart :points="trendPoints" :unit="m.unit" />
        </el-tab-pane>
      </el-tabs>

      <p class="chart-note">
        某天没有记录时该点断开，不补 0 也不沿用前一天的值——避免把"没测"看成"测了等于 0"。
      </p>
    </el-card>

    <el-empty v-if="!loading && !summary" description="暂无法加载概览数据" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getHealthSummary } from '@/api/health'
import DataTrendChart from '@/components/DataTrendChart.vue'

const loading = ref(false)
const summary = ref(null)
const activeMetric = ref('')

const metricsWithData = computed(() =>
  (summary.value?.metrics || []).filter((m) => m.sampleCount > 0)
)

/**
 * 把后端的按天分组转成图表需要的点集。
 * null 必须原样传下去——ECharts 配 connectNulls: false 才会断开。
 */
const trendPoints = computed(() => {
  const active = activeMetric.value || metricsWithData.value[0]?.metric
  if (!active) {
    return []
  }
  const daily = summary.value?.daily || []
  return daily.map((d) => {
    const v = d.averages?.[active]
    return {
      date: d.date,
      label: formatDate(d.date),
      value: v === null || v === undefined ? null : Number(v)
    }
  })
})

/** 只显示月-日，坐标轴才不会被七个完整日期挤满 */
function formatDate(dateStr) {
  if (!dateStr || dateStr.length < 10) {
    return dateStr
  }
  return dateStr.slice(5).replace('-', '/')
}

async function load() {
  loading.value = true
  try {
    const res = await getHealthSummary()
    summary.value = res.data
    const first = metricsWithData.value[0]
    if (first) {
      activeMetric.value = first.metric
    }
  } catch (error) {
    summary.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.health-summary {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 16px;
  font-weight: 500;
}

.as-of {
  font-size: 12px;
  color: #909399;
}

.metric-card {
  text-align: center;
}

.metric-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.metric-value {
  font-size: 22px;
  font-weight: 500;
  color: #303133;
}

.metric-unit {
  font-size: 12px;
  color: #909399;
  margin-left: 4px;
}

.metric-detail {
  font-size: 12px;
  color: #a8abb2;
  margin-top: 4px;
}

.metric-hit {
  font-size: 12px;
  color: #e6a23c;
  margin-top: 4px;
}

.muted {
  color: #c0c4cc;
  font-size: 16px;
}

.chart-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
