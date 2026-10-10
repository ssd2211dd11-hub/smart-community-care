<template>
  <div class="trend-chart">
    <div v-if="!hasData" class="empty-slot">
      <el-empty :description="emptyText" :image-size="70" />
    </div>
    <div v-else ref="chartRef" class="chart-area" />
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  /** [{ date, label, value }]，value 为 null 表示当天缺测 */
  points: { type: Array, default: () => [] },
  title: { type: String, default: '' },
  unit: { type: String, default: '' },
  color: { type: String, default: '#88543e' },
  emptyText: { type: String, default: '暂无数据' }
})

const chartRef = ref(null)
let chart = null

// 全部缺测时不画图。补 0 或复制前值都会让图看起来"有数据"，
// 那比空白更误导——空白至少说明确实没测。
const hasData = computed(() => props.points.some((p) => p.value !== null && p.value !== undefined))

function render() {
  if (!hasData.value) {
    return
  }
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  // connectNulls: false —— 缺测日断开，不要连成一条线
  chart.setOption({
    title: props.title ? { text: props.title, left: 'center', textStyle: { fontSize: 14 } } : undefined,
    tooltip: {
      trigger: 'axis',
      // null 值不参与展示
      axisPointer: { type: 'line' }
    },
    grid: { left: 50, right: 20, top: props.title ? 45 : 25, bottom: 40 },
    xAxis: {
      type: 'category',
      data: props.points.map((p) => p.label),
      axisLabel: { fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: props.unit,
      nameTextStyle: { fontSize: 11 },
      axisLabel: { fontSize: 11 }
    },
    series: [
      {
        type: 'line',
        smooth: false,
        connectNulls: false,
        data: props.points.map((p) => p.value),
        itemStyle: { color: props.color },
        lineStyle: { color: props.color, width: 2 },
        // 明确标出每个采样点，便于读数
        label: { show: false }
      }
    ]
  })
}

function resize() {
  if (chart) {
    chart.resize()
  }
}

onMounted(async () => {
  await nextTick()
  render()
  window.addEventListener('resize', resize)
})

watch(
  () => props.points,
  () => {
    if (chart) {
      render()
    } else {
      nextTick(render)
    }
  },
  { deep: true }
)

// 页面切换时容器被销毁，ECharts 实例必须显式释放，
// 否则 resize 监听与内部定时器会一直挂着。
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.trend-chart {
  width: 100%;
}

.chart-area {
  width: 100%;
  height: 260px;
}

.empty-slot {
  height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
