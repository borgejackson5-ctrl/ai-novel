<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getNovelDetail,
  getReviewOverview,
  startNovelReview,
  getReviewTask,
  getReviewIssues,
  resumeNovelReview,
  cancelNovelReview
} from '../api'
import WorkbenchTabs from '../components/WorkbenchTabs.vue'

const route = useRoute()
const router = useRouter()
const novelId = route.params.id

const loading = ref(true)
const novel = ref(null)
const overview = ref(null)
const task = ref(null)
const starting = ref(false)

const issues = ref([])
const issuePage = ref(1)
const issueSize = 10
const issueTotal = ref(0)
const issueLoading = ref(false)

// 审查在服务端逐章执行，页面只需定期查询一次进度。
// 间隔 3 秒：略长于单次调用的耗时，既能及时反映进度，又不会造成页面频繁刷新。
const POLL_MS = 3000
let timer = null

// 额度按字下发（量级为「万」），直接显示原数字不易阅读，与「AI 设置」页使用同一口径
const fmtUnits = (n) => {
  const v = Number(n) || 0
  if (v < 10000) return String(v)
  const w = v / 10000
  return (Number.isInteger(w) ? w : w.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')) + ' 万'
}

const novelTitle = computed(() => novel.value?.title || '')
const chapterCount = computed(() => overview.value?.chapterCount ?? 0)
const totalWords = computed(() => overview.value?.totalWords ?? 0)
const useOwnKey = computed(() => !!overview.value?.useOwnKey)
const remaining = computed(() => overview.value?.remainingUnits)

// 审查范围：整本 / 最近 N 章。长篇整本通常一次无法跑完（233 章的稿件需两万多字额度，
// 免费额度每天仅 3 万字），因此需可选范围，使单次审查能够完成并得到完整结论
const scope = ref('ALL')
const scopeOptions = computed(() => overview.value?.scopeOptions || [])
const scopeKey = (o) => (o.scope === 'ALL' ? 'ALL' : `RECENT:${o.recentCount}`)
const scopeLabel = (o) =>
  o.scope === 'ALL'
    ? `整本（${o.chapters} 章 · 约 ${fmtUnits(o.words)}字）`
    : `最近 ${o.recentCount} 章（约 ${fmtUnits(o.words)}字）`
const selectedEstimate = computed(
  () => scopeOptions.value.find((o) => scopeKey(o) === scope.value) || null
)

const scaleText = computed(() =>
  chapterCount.value ? `共 ${chapterCount.value} 章 · 约 ${fmtUnits(totalWords.value)}字` : '还没有章节'
)

// 预估无法审完时提前提示，而非等到作者开始、已审十几章后再中止
const quotaHint = computed(() => {
  if (useOwnKey.value) return '这份稿子会用你自己的 Key 审查，不占用免费额度'
  if (remaining.value == null) return ''
  const est = selectedEstimate.value
  if (est && !est.enoughQuota) {
    return `今天还剩 ${fmtUnits(remaining.value)}字，跑不完所选范围（约 ${fmtUnits(est.words)}字）` +
      '—— 换小一点的范围，或者先审一段、明天接着审'
  }
  if (est) {
    return `今天还剩 ${fmtUnits(remaining.value)}字，够审完所选范围（约 ${fmtUnits(est.words)}字）`
  }
  return ''
})

const running = computed(() => !!task.value?.running)
const hasTask = computed(() => !!task.value)
const doneText = computed(() => {
  if (!task.value) return ''
  return `${task.value.doneChapters}/${task.value.totalChapters} 章`
})

const statusType = computed(() => {
  const s = task.value?.status
  if (s === 2) return 'success'
  if (s === 3) return 'warning'
  if (s === 4) return 'danger'
  return ''
})

// 类型由服务端收敛为四类之一（模型常在类型后附加括号说明，直接用于上色会不匹配）
const typeTagType = (type) => {
  if (type === '错别字') return 'danger'
  if (type === '语病') return 'warning'
  if (type === '标点') return 'info'
  if (type === '前后不一致') return 'primary'
  return 'info'
}

const loadIssues = async (page = 1) => {
  if (!task.value?.taskId) return
  issueLoading.value = true
  issuePage.value = page
  try {
    const res = await getReviewIssues(task.value.taskId, { pageNum: page, pageSize: issueSize })
    issues.value = res?.list || []
    issueTotal.value = Number(res?.total || 0)
  } finally {
    issueLoading.value = false
  }
}

const applyTask = async (next) => {
  task.value = next
  if (!next) return
  if (next.running) {
    startPolling()
    return
  }
  stopPolling()
  if ((next.issueCount || 0) > 0) await loadIssues(1)
  else {
    issues.value = []
    issueTotal.value = 0
  }
}

const loadOverview = async () => {
  const res = await getReviewOverview(novelId)
  overview.value = res
  await applyTask(res?.task || null)
}

const load = async () => {
  loading.value = true
  try {
    novel.value = await getNovelDetail(novelId)
    await loadOverview()
  } finally {
    loading.value = false
  }
}

const startPolling = () => {
  if (timer) return
  timer = setInterval(async () => {
    try {
      const next = await getReviewTask(task.value.taskId)
      await applyTask(next)
    } catch (e) {
      stopPolling()
    }
  }, POLL_MS)
}

const stopPolling = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

const doStart = async () => {
  const chapters = chapterCount.value
  if (!chapters) {
    ElMessage.warning('这本书还没有章节，先写点内容再审查')
    return
  }
  const est = selectedEstimate.value
  const scopeDesc = est
    ? (est.scope === 'ALL' ? `全书 ${est.chapters} 章` : `最近 ${est.recentCount} 章`)
    : `全书 ${chapters} 章`
  try {
    await ElMessageBox.confirm(
      `将对${scopeDesc}、约 ${fmtUnits(est ? est.words : totalWords.value)}字做一次错别字与语病自查。` +
        '审查需要一些时间，可以离开这个页面，回来接着看进度。',
      '开始审查',
      { confirmButtonText: '开始审查', cancelButtonText: '再想想', type: 'info' }
    )
  } catch (e) {
    return
  }

  starting.value = true
  try {
    const payload = scope.value.startsWith('RECENT:')
      ? { scope: 'RECENT', recentCount: Number(scope.value.split(':')[1]) }
      : { scope: 'ALL' }
    const next = await startNovelReview(novelId, payload)
    await applyTask(next)
    if (next?.running) ElMessage.success('已经开始审查，进度会自动刷新')
  } catch (e) {
    // 额度不足 / 平台忙这类拒绝由拦截器统一提示，这里不再叠一层
  } finally {
    starting.value = false
  }
}

const doResume = async () => {
  starting.value = true
  try {
    const next = await resumeNovelReview(task.value.taskId)
    await applyTask(next)
    ElMessage.success('已接着审查还没完成的部分')
  } catch (e) {
    // 同上
  } finally {
    starting.value = false
  }
}

const doCancel = async () => {
  try {
    await ElMessageBox.confirm(
      '已经审过的章节和结果会保留，下次可以接着审没审完的部分。',
      '停止这次审查',
      { confirmButtonText: '停止', cancelButtonText: '继续审查', type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await cancelNovelReview(task.value.taskId)
    await applyTask(await getReviewTask(task.value.taskId))
    ElMessage.success('已停止')
  } catch (e) {
    // 同上
  }
}

const goChapter = (row) => {
  router.push({
    path: `/novel/${novelId}/manage`,
    query: { focus: row.chapterNo }
  })
}

const excerptText = (row) => row.excerpt || ''

onMounted(load)
onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="page">
    <WorkbenchTabs :novel-id="novelId" active="review" />

    <div v-loading="loading" class="wrap">
      <div class="head">
        <div class="head-main">
          <h2 class="title">{{ novelTitle || '全书审查' }}</h2>
          <span class="scale">{{ scaleText }}</span>
        </div>
        <el-button
          v-if="!running"
          type="primary"
          round
          :loading="starting"
          :disabled="!chapterCount"
          @click="hasTask && task.canResume ? doResume() : doStart()"
        >
          {{ hasTask ? (task.canResume ? '继续审查' : '重新审查') : '开始审查' }}
        </el-button>
        <el-button v-else round @click="doCancel">停止审查</el-button>
      </div>

      <!-- 审查范围：只在还没发起过时给选（已有任务时按钮变成「继续/重新审查」，
           重新审查会按新范围再建任务，那时也让它能选） -->
      <div v-if="scopeOptions.length > 1" class="scope-row">
        <span class="scope-label">审查范围</span>
        <el-radio-group v-model="scope" size="small">
          <el-radio-button v-for="o in scopeOptions" :key="scopeKey(o)" :value="scopeKey(o)">
            {{ scopeLabel(o) }}
            <span v-if="!o.enoughQuota" class="scope-warn">额度不够</span>
          </el-radio-button>
        </el-radio-group>
      </div>

      <div
        v-if="quotaHint"
        class="tip"
        :class="{ warn: !useOwnKey && selectedEstimate && !selectedEstimate.enoughQuota }"
      >
        {{ quotaHint }}
      </div>

      <div v-if="!hasTask" class="empty">
        <div class="empty-title">还没有做过全书审查</div>
        <div class="empty-desc">
          开始后会逐章检查错别字、语病、标点，以及人名与专有名词在前后文里的写法是否一致，
          结果按章列出，方便逐条修改。
        </div>
      </div>

      <div v-else class="card">
        <div class="card-head">
          <div class="card-title">
            <el-tag :type="statusType" size="small" effect="light" round>{{ task.statusText }}</el-tag>
            <span class="progress-text">{{ doneText }}</span>
            <span v-if="task.issueCount" class="issue-count">发现 {{ task.issueCount }} 处问题</span>
          </div>
          <div v-if="!useOwnKey && task.chargedUnits" class="charge">
            本次消耗 {{ fmtUnits(task.chargedUnits) }}字
            <span v-if="task.refundedUnits">（已退回 {{ fmtUnits(task.refundedUnits) }}字）</span>
          </div>
        </div>

        <el-progress
          :percentage="task.percent || 0"
          :status="task.status === 2 ? 'success' : task.status === 4 ? 'exception' : ''"
          :stroke-width="10"
        />

        <div v-if="task.message" class="task-message">{{ task.message }}</div>

        <div v-if="task.failedList && task.failedList.length" class="failed">
          <div class="failed-title">这几章没能完成审查</div>
          <div v-for="f in task.failedList" :key="f.chapterId" class="failed-item">
            <span class="failed-chapter">第 {{ f.chapterNo || '?' }} 章 {{ f.chapterTitle || '' }}</span>
            <span class="failed-reason">{{ f.message }}</span>
          </div>
          <div v-if="task.failedListTruncated" class="failed-more">
            还有 {{ task.failedListTruncated }} 章未列出
          </div>
        </div>
      </div>

      <div v-if="hasTask && task.issueCount > 0" class="card">
        <div class="card-head">
          <div class="card-title"><span class="section">问题清单</span></div>
          <div class="charge">同一处问题在多章出现时只列一行</div>
        </div>

        <el-table v-loading="issueLoading" :data="issues" stripe>
          <el-table-column label="章节" width="150">
            <template #default="{ row }">
              <a class="chapter-link" @click="goChapter(row)">
                第 {{ row.chapterNo }} 章 {{ row.chapterTitle || '' }}
              </a>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag :type="typeTagType(row.type)" size="small" effect="plain" round>
                {{ row.type || '问题' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="原文" min-width="200">
            <template #default="{ row }">
              <span class="excerpt">{{ excerptText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="suggestion" label="建议" min-width="200" show-overflow-tooltip />
          <el-table-column label="也出现在" min-width="120">
            <template #default="{ row }">
              <span v-if="row.chapterCount > 1" class="also">
                另 {{ row.chapterCount - 1 }} 章（第 {{ row.chapterNos }} 章）
              </span>
              <span v-else class="also-none">仅此一处</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            layout="prev, pager, next"
            :total="issueTotal"
            :page-size="issueSize"
            :current-page="issuePage"
            @current-change="loadIssues"
          />
        </div>
      </div>

      <div v-else-if="hasTask && !running && !task.failedChapters" class="card done-card">
        全书审查完成，没有发现问题。
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { max-width: 1100px; margin: 0 auto; padding: 24px 20px 60px; }
.wrap { min-height: 320px; }
.head { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
.head-main { display: flex; align-items: baseline; gap: 12px; min-width: 0; }
.title { font-family: var(--serif); font-size: 22px; color: var(--ink); margin: 0; }
.scale { font-size: 13px; color: var(--muted); white-space: nowrap; }
.head .el-button { margin-left: auto; }

.tip {
  font-size: 12.5px;
  color: var(--muted);
  background: var(--paper-2);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 14px;
  margin-bottom: 14px;
}
.tip.warn { color: var(--ink); background: var(--cinnabar-bg); border-color: rgba(178, 58, 46, .18); }

.empty {
  background: var(--paper-2);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 40px 24px;
  text-align: center;
}
.empty-title { font-family: var(--serif); font-size: 16px; color: var(--ink); margin-bottom: 8px; }
.empty-desc { font-size: 13px; color: var(--muted); line-height: 1.9; max-width: 560px; margin: 0 auto; }

.card {
  background: var(--paper-2);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 18px 20px;
  margin-bottom: 16px;
}
.card-head { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.card-title { display: flex; align-items: center; gap: 10px; }
.progress-text { font-size: 13px; color: var(--ink-2); }
.issue-count { font-size: 13px; color: var(--cinnabar); }
.section { font-family: var(--serif); font-size: 15px; color: var(--ink); }
.charge { margin-left: auto; font-size: 12.5px; color: var(--muted); }

.task-message {
  margin-top: 12px;
  font-size: 13px;
  color: var(--ink-2);
  background: var(--paper);
  border-radius: 8px;
  padding: 10px 12px;
}

.failed { margin-top: 16px; border-top: 1px dashed var(--line); padding-top: 12px; }
.failed-title { font-size: 13px; color: var(--ink); margin-bottom: 8px; }
.failed-item { display: flex; gap: 12px; font-size: 12.5px; line-height: 1.9; }
.failed-chapter { color: var(--ink-2); white-space: nowrap; }
.failed-reason { color: var(--muted); }
.failed-more { font-size: 12.5px; color: var(--muted); margin-top: 6px; }

.chapter-link { color: var(--cinnabar); cursor: pointer; font-size: 13px; }
.chapter-link:hover { text-decoration: underline; }
.excerpt { font-size: 13px; color: var(--ink); word-break: break-all; }
.also { font-size: 12.5px; color: var(--muted); }
.also-none { font-size: 12.5px; color: var(--line); }

.pager { display: flex; justify-content: flex-end; margin-top: 14px; }
.done-card { text-align: center; font-size: 14px; color: var(--ink-2); padding: 36px 20px; }

.scope-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}
.scope-label { font-size: 13px; color: var(--muted); }
.scope-warn { margin-left: 4px; font-size: 11px; opacity: .75; }

@media (max-width: 900px) {
  .head { flex-wrap: wrap; }
  .head .el-button { margin-left: 0; }
}
</style>
