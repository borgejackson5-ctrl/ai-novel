<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getSystemMetrics,
  resetSystemMetrics,
  rebuildSearchIndex,
  reconcileSearchIndex
} from '../../api'

const loading = ref(true)
const data = ref(null)

/**
 * 死信队列名 → 展示名。
 *
 * 队列名是内部标识（形如 ai.novel.audit.dlx.queue），直接显示等于把内部结构摆给使用者看。
 * 未登记的队列回落为原名：新增队列时至少能看出是哪一条，而不是整行消失。
 */
const DLX_LABEL = {
  'ai.novel.audit.dlx.queue': 'AI 预审',
  'ai.novel.review.dlx.queue': '全文审查',
  'search.novel.sync.dlx.queue': '检索同步',
  'search.chapter.chunk.dlx.queue': '章节检索',
  'oss.delete.dlx.queue': '文件清理',
  'pay.order.close.dlx.queue': '订单超时关闭',
  'rank.read.dlx.queue': '阅读数落库'
}

/** 积压读数：-1 表示读不到（与 0「一切正常」区分），null 表示本次没取到 */
const fmtBacklog = (n) => (n == null ? '-' : Number(n) < 0 ? '未知' : Number(n))

/** 需要提醒的情况：有积压，或读数未知。未知不能当成正常 —— 那恰是最需要排查的时候 */
const backlogAlert = (n) => n == null || Number(n) < 0 || Number(n) > 0

const dlxRows = computed(() =>
  Object.entries(data.value?.dlxQueues || {}).map(([queue, count]) => ({
    queue,
    count,
    label: DLX_LABEL[queue] || queue
  }))
)

const load = async () => {
  loading.value = true
  try {
    data.value = await getSystemMetrics()
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

const doReset = async () => {
  try {
    await ElMessageBox.confirm(
      '重置只清空统计基线，不影响任何业务数据。适合排查完一个问题后重新观察。',
      '重置统计',
      { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await resetSystemMetrics()
    ElMessage.success('统计已重置')
    load()
  } catch (e) { /* 拦截器已提示 */ }
}

// ===== 检索数据维护 =====
// 这两个动作原仅有后端接口、无前端入口，排查时只能手工发起请求。
const rebuilding = ref(false)
const reconciling = ref(false)
const rebuiltCount = ref(null)
const reconcileResult = ref(null)

const doRebuild = async () => {
  try {
    await ElMessageBox.confirm(
      '会按当前全部已上架作品重新生成一遍检索数据。作品多时需要一些时间，期间检索结果可能暂时不完整。',
      '重建检索数据',
      { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  rebuilding.value = true
  try {
    rebuiltCount.value = Number((await rebuildSearchIndex()) || 0)
    reconcileResult.value = null
    ElMessage.success(`已处理 ${rebuiltCount.value} 部作品`)
  } catch (e) { /* 拦截器已提示 */ } finally {
    rebuilding.value = false
  }
}

const doReconcile = async () => {
  reconciling.value = true
  try {
    reconcileResult.value = await reconcileSearchIndex()
    const r = reconcileResult.value
    if (r?.skipped) {
      ElMessage.warning('差异超出安全阈值，已中止且未做任何改动')
    } else if (r?.consistent) {
      ElMessage.success('比对结果一致')
    } else {
      ElMessage.success('已按比对结果完成修复')
    }
  } catch (e) { /* 拦截器已提示 */ } finally {
    reconciling.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="section-title">系统健康</h2>
        <span class="count">
          启动于 {{ data?.runningSince || '-' }}
        </span>
      </div>
      <el-button round @click="doReset">重置统计</el-button>
    </div>

    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-num">{{ data?.totalRequests ?? 0 }}</div>
        <div class="stat-label">累计请求数</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ data?.slowApiThresholdMs ?? '-' }}<span class="unit">ms</span></div>
        <div class="stat-label">慢请求阈值</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ data?.slowSqlThresholdMs ?? '-' }}<span class="unit">ms</span></div>
        <div class="stat-label">慢查询阈值</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ data?.apis?.length ?? 0 }}</div>
        <div class="stat-label">已统计接口数</div>
      </div>
    </div>

    <section class="ops">
      <div class="ops-head">
        <h3 class="block-title">消息投递</h3>
        <span class="ops-hint">消息发不出去、或投递反复失败时，从这里看积压</span>
      </div>

      <div class="stat-row mq-row">
        <div class="stat-card">
          <div class="stat-num" :class="{ zero: !backlogAlert(data?.outboxBacklog) }">
            {{ fmtBacklog(data?.outboxBacklog) }}
          </div>
          <div class="stat-label">待补投消息</div>
        </div>
        <div class="stat-card">
          <div class="stat-num" :class="{ zero: !backlogAlert(data?.dlxBacklog) }">
            {{ fmtBacklog(data?.dlxBacklog) }}
          </div>
          <div class="stat-label">投递失败积压</div>
        </div>
      </div>

      <div v-if="dlxRows.length" class="ops-result">
        <span class="ops-sub">按类型：</span>
        <span v-for="r in dlxRows" :key="r.queue" class="dlx-chip" :class="{ bad: backlogAlert(r.count) }">
          {{ r.label }} {{ fmtBacklog(r.count) }}
        </span>
      </div>

      <div v-if="backlogAlert(data?.dlxBacklog)" class="ops-result warn">
        有消息反复投递失败并已堆积。继续增长会导致消息服务拒绝写入，
        届时全站都无法发出消息，请尽快排查。
      </div>
    </section>

    <section class="ops">
      <div class="ops-head">
        <h3 class="block-title">检索数据维护</h3>
        <span class="ops-hint">检索到的作品与实际对不上时，用这两步处理</span>
      </div>

      <div class="ops-row">
        <div class="ops-item">
          <div class="ops-name">重建检索数据</div>
          <div class="ops-desc">按当前全部已上架作品重新生成一遍，用于检索内容明显缺漏或长期未更新时</div>
          <el-button :loading="rebuilding" @click="doRebuild">开始重建</el-button>
        </div>
        <div class="ops-item">
          <div class="ops-name">核对一致性</div>
          <div class="ops-desc">比对两边差异并自动修复；差异超出安全阈值时会中止，不做任何改动</div>
          <el-button :loading="reconciling" @click="doReconcile">开始核对</el-button>
        </div>
      </div>

      <div v-if="rebuiltCount !== null" class="ops-result">
        上次重建：已处理 <b>{{ rebuiltCount }}</b> 部作品
      </div>

      <div v-if="reconcileResult" class="ops-result">
        <template v-if="reconcileResult.skipped">
          <span class="warn">差异超出安全阈值，已中止且未做任何改动，请人工确认后重试</span>
          <span class="ops-sub">
            （比对时应有 {{ reconcileResult.expectedCount }} 部、实际 {{ reconcileResult.indexedCount }} 部，
            其中 {{ reconcileResult.staleCount }} 部疑似失效）
          </span>
        </template>
        <template v-else>
          <span>应有 <b>{{ reconcileResult.expectedCount }}</b> 部，实际 <b>{{ reconcileResult.indexedCount }}</b> 部；</span>
          <span>缺漏 <b>{{ reconcileResult.missingCount }}</b>、多余 <b>{{ reconcileResult.staleCount }}</b>；</span>
          <span>已补 <b>{{ reconcileResult.repairedCount }}</b>、已清 <b>{{ reconcileResult.removedCount }}</b>；</span>
          <span :class="reconcileResult.consistent ? 'ok' : 'warn'">
            {{ reconcileResult.consistent ? '比对结果一致' : '存在差异，已修复' }}
          </span>
          <span class="ops-sub">（用时 {{ reconcileResult.costMs }} ms）</span>
        </template>
      </div>
    </section>

    <h3 class="block-title">接口耗时</h3>
    <el-table :data="data?.apis || []" size="small" empty-text="暂无数据">
      <el-table-column prop="api" label="接口" min-width="260" show-overflow-tooltip />
      <el-table-column prop="count" label="调用次数" width="100" align="right" />
      <el-table-column prop="avgMs" label="平均耗时" width="110" align="right">
        <template #default="{ row }">{{ row.avgMs }} ms</template>
      </el-table-column>
      <el-table-column prop="maxMs" label="最大耗时" width="110" align="right">
        <template #default="{ row }">
          <span :class="{ hot: row.maxMs >= (data?.slowApiThresholdMs || 500) }">{{ row.maxMs }} ms</span>
        </template>
      </el-table-column>
      <el-table-column prop="slowCount" label="慢次数" width="90" align="right" />
      <el-table-column prop="slowRatio" label="慢占比" width="100" align="right">
        <template #default="{ row }">{{ row.slowRatio }}%</template>
      </el-table-column>
    </el-table>

    <h3 class="block-title">最近的慢请求</h3>
    <el-table :data="data?.recentSlowApis || []" size="small" empty-text="暂无慢请求">
      <el-table-column prop="name" label="接口" min-width="260" show-overflow-tooltip />
      <el-table-column prop="costMs" label="耗时" width="110" align="right">
        <template #default="{ row }">{{ row.costMs }} ms</template>
      </el-table-column>
      <el-table-column prop="time" label="发生时间" width="140" />
    </el-table>

    <h3 class="block-title">最近的慢查询</h3>
    <el-table :data="data?.recentSlowSqls || []" size="small" empty-text="暂无慢查询">
      <el-table-column prop="name" label="查询来源" width="280" show-overflow-tooltip />
      <el-table-column prop="costMs" label="耗时" width="110" align="right">
        <template #default="{ row }">{{ row.costMs }} ms</template>
      </el-table-column>
      <el-table-column prop="detail" label="查询语句" min-width="320" show-overflow-tooltip />
      <el-table-column prop="time" label="发生时间" width="140" />
    </el-table>
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
.stat-num .unit { font-size: 13px; font-weight: 400; color: var(--muted); margin-left: 3px; }
/* 积压为 0 时改用低调色：默认的告警红与「真的有积压」长得一样，看久了会忽略异常 */
.stat-num.zero { color: #7c818e; }
.stat-label { margin-top: 6px; font-size: 12.5px; color: #7c818e; }

.mq-row { grid-template-columns: repeat(2, 1fr); margin-bottom: 12px; }

.dlx-chip {
  display: inline-block;
  margin: 0 10px 6px 0;
  padding: 2px 9px;
  border-radius: 4px;
  background: var(--paper-2, #f2f0ec);
  font-size: 12.5px;
}
.dlx-chip.bad { color: #b23a2e; font-weight: 600; }

.block-title {
  font-size: 14px;
  font-weight: 500;
  margin: 22px 0 10px;
}
.hot { color: #b23a2e; }

.ops { margin-top: 22px; }
.ops-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.ops-head .block-title { margin-bottom: 10px; }
.ops-hint { font-size: 12.5px; color: var(--muted); }

.ops-row {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
.ops-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 14px 16px;
  background: var(--paper);
  border-radius: 10px;
}
.ops-name { font-size: 14px; font-weight: 500; }
.ops-desc { font-size: 12.5px; color: #7c818e; line-height: 1.7; }

.ops-result {
  margin-top: 12px;
  padding: 12px 16px;
  background: var(--paper);
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--muted);
}
.ops-result b { color: #2a2d34; font-weight: 600; }
.ops-sub { color: var(--muted); }
.ok { color: #2f7d54; }
.warn { color: #b23a2e; }

@media (max-width: 900px) {
  .ops-row, .stat-row { grid-template-columns: 1fr; }
}
</style>
