<script setup>
import { computed, onMounted, ref } from 'vue'
import { getAiStats } from '../../api'

const loading = ref(true)
const data = ref(null)

const load = async () => {
  loading.value = true
  try {
    data.value = await getAiStats()
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

/** 比率转百分比文本；无数据时用破折号，避免显示成 0% 而被误读为「一次都没命中」 */
const pct = (v) => (typeof v === 'number' ? `${(v * 100).toFixed(1)}%` : '—')

/**
 * 缓存命中率按全部缓存合并计算：分母取总读取次数而非各缓存命中率的平均，
 * 后者会让读取量很小的缓存与主要缓存等权。
 */
const hitRate = computed(() => {
  const list = data.value?.cache || []
  const hit = list.reduce((sum, item) => sum + item.hit, 0)
  const reads = list.reduce((sum, item) => sum + item.hit + item.miss, 0)
  return reads === 0 ? '—' : `${((hit / reads) * 100).toFixed(1)}%`
})

/** el-progress 要求 0~100 的整数；上限为 0（已关闭该配额）时按 0 处理，不出现 NaN */
const quotaPct = (q) => {
  if (!q.limit) return 0
  return Math.min(100, Math.round((q.used / q.limit) * 100))
}

const quotaStatus = (q) => (q.limit && q.used >= q.limit ? 'exception' : undefined)

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="section-title">AI 用量</h2>
        <span class="count">
          采集自 {{ data?.generatedAt || '-' }} —— {{ data?.counterScope || '' }}
        </span>
      </div>
      <el-button round @click="load">刷新</el-button>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-num">{{ data?.totals?.succeeded ?? 0 }}</div>
        <div class="stat-label">调用成功</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ data?.totals?.failed ?? 0 }}</div>
        <div class="stat-label">调用失败</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ pct(data?.totals?.successRate) }}</div>
        <div class="stat-label">成功率</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ hitRate }}</div>
        <div class="stat-label">缓存命中率</div>
      </div>
    </div>

    <h3 class="block-title">按场景与模型</h3>
    <el-table :data="data?.scenes || []" size="small">
      <el-table-column prop="label" label="场景" width="130" />
      <el-table-column prop="model" label="模型" min-width="160" show-overflow-tooltip />
      <el-table-column prop="succeeded" label="成功" width="90" align="right" />
      <el-table-column prop="failed" label="失败" width="90" align="right" />
      <el-table-column label="平均耗时" width="110" align="right">
        <template #default="{ row }">{{ row.avgCostMs }} ms</template>
      </el-table-column>
      <el-table-column label="最大耗时" width="110" align="right">
        <template #default="{ row }">{{ row.maxCostMs }} ms</template>
      </el-table-column>
    </el-table>

    <h3 class="block-title">当日额度</h3>
    <div class="quota-row">
      <div v-for="q in data?.quota || []" :key="q.name" class="quota-card">
        <div class="quota-head">
          <span class="quota-name">{{ q.name }}</span>
          <span class="quota-num">{{ q.used }} / {{ q.limit }}</span>
        </div>
        <el-progress
          :percentage="quotaPct(q)"
          :status="quotaStatus(q)"
          :stroke-width="8"
          :show-text="false"
        />
        <div class="quota-left">剩余 {{ q.remaining }}</div>
      </div>
    </div>

    <h3 class="block-title">缓存命中</h3>
    <el-table :data="data?.cache || []" size="small">
      <el-table-column prop="cache" label="缓存" min-width="200" show-overflow-tooltip />
      <el-table-column prop="hit" label="命中" width="100" align="right" />
      <el-table-column prop="miss" label="未命中" width="100" align="right" />
      <el-table-column label="命中率" width="110" align="right">
        <template #default="{ row }">{{ pct(row.hitRate) }}</template>
      </el-table-column>
    </el-table>

    <div class="two-col">
      <div>
        <h3 class="block-title">降级</h3>
        <el-table :data="data?.degrade || []" size="small">
          <el-table-column prop="label" label="场景" width="120" />
          <el-table-column prop="reason" label="原因" min-width="130" />
          <el-table-column prop="count" label="次数" width="80" align="right" />
        </el-table>
      </div>
      <div>
        <h3 class="block-title">流式收尾</h3>
        <el-table :data="data?.sse || []" size="small">
          <el-table-column prop="api" label="入口" width="110" />
          <el-table-column prop="outcome" label="结果" min-width="110" />
          <el-table-column prop="count" label="次数" width="80" align="right" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-head .section-title { margin-bottom: 4px; }
.count { color: var(--muted); font-size: 12.5px; }

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 22px;
}
.stat-card {
  padding: 16px 18px;
  background: var(--paper);
  border-radius: 10px;
}
.stat-num { font-size: 26px; font-weight: 700; color: #b23a2e; line-height: 1.1; }
.stat-label { margin-top: 6px; font-size: 12.5px; color: #7c818e; }

.block-title {
  font-size: 14px;
  font-weight: 500;
  margin: 22px 0 10px;
}

.quota-row {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
.quota-card {
  padding: 14px 16px;
  background: var(--paper);
  border-radius: 10px;
}
.quota-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 10px;
}
.quota-name { font-size: 13.5px; }
.quota-num { font-size: 15px; font-weight: 600; }
.quota-left { margin-top: 8px; font-size: 12.5px; color: #7c818e; }

.two-col {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}
.two-col .block-title { margin-top: 18px; }

@media (max-width: 900px) {
  .stat-row, .quota-row, .two-col { grid-template-columns: 1fr; }
}
</style>
