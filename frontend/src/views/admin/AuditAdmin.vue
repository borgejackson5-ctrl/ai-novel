<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAuditPage,
  auditPass,
  auditReject,
  getCategories,
  getChapterAuditPage,
  auditChapterPass,
  auditChapterReject
} from '../../api'
import { fmtTime } from '../../utils/format'
import { posterVars, posterChar } from '../../utils/cover'

const mode = ref('novel')
const categories = ref([])

const auditMap = {
  0: { text: '待审核', type: 'warning' },
  1: { text: '已通过', type: 'success' },
  2: { text: '已拒绝', type: 'danger' },
  3: { text: '修改审核中', type: 'warning' },
  4: { text: '重新上架待审', type: 'warning' }
}
const chapterAuditMap = {
  0: { text: '待审核', type: 'warning' },
  3: { text: '修改审核中', type: 'warning' }
}

/* ==================== 作品审核 ==================== */
const loading = ref(false)
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 20
const auditStatus = ref(0)

const current = ref(null)
const passCategoryId = ref(null)
const passBusy = ref(false)
const rejectOpen = ref(false)
const rejectReason = ref('')
const rejectBusy = ref(false)
const checkedIds = ref([])

const load = async () => {
  loading.value = true
  try {
    const res = await getAuditPage({
      pageNum: pageNum.value,
      pageSize,
      auditStatus: auditStatus.value
    })
    list.value = res?.list || []
    total.value = Number(res?.total || 0)
    checkedIds.value = []
    select(list.value[0] || null)
  } finally {
    loading.value = false
  }
}

const select = (row) => {
  current.value = row
  passCategoryId.value = row?.categoryId ?? null
  rejectOpen.value = false
  rejectReason.value = ''
}

const onStatus = (v) => {
  auditStatus.value = v
  pageNum.value = 1
  load()
}
const onPage = (p) => {
  pageNum.value = p
  load()
}

const toggleCheck = (id, v) => {
  const s = new Set(checkedIds.value)
  if (v) s.add(id)
  else s.delete(id)
  checkedIds.value = [...s]
}

const doPass = async () => {
  if (!current.value) return
  passBusy.value = true
  try {
    await auditPass(current.value.id, passCategoryId.value)
    ElMessage.success(`《${current.value.title}》已通过并上架，已通知作者`)
    load()
  } finally {
    passBusy.value = false
  }
}

const doReject = async () => {
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写拒绝理由，作者需要知道原因')
    return
  }
  rejectBusy.value = true
  try {
    await auditReject(current.value.id, { reason: rejectReason.value.trim() })
    ElMessage.success('已拒绝并通知作者')
    load()
  } finally {
    rejectBusy.value = false
  }
}

const batchPass = async () => {
  const rows = list.value.filter((r) => checkedIds.value.includes(r.id))
  if (!rows.length) return
  try {
    await ElMessageBox.confirm(
      `确认批量通过选中的 ${rows.length} 部作品？将通过并自动上架，并以站内信通知作者。`,
      '批量通过',
      { type: 'warning', confirmButtonText: '确认通过', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  passBusy.value = true
  let ok = 0
  try {
    for (const r of rows) {
      try {
        await auditPass(r.id, r.categoryId)
        ok += 1
      } catch (e) { /* 单条失败不阻断，最后汇总 */ }
    }
  } finally {
    passBusy.value = false
  }
  ElMessage.success(`已通过 ${ok} 部作品${ok < rows.length ? `，${rows.length - ok} 部失败` : ''}`)
  load()
}

/* ==================== 章节审核 ==================== */
const chapterLoading = ref(false)
const chapterList = ref([])
const chapterTotal = ref(0)
const chapterPageNum = ref(1)
const chapterStatus = ref(null)
const chapterCurrent = ref(null)
const chapterPassBusy = ref(false)
const chapterRejectOpen = ref(false)
const chapterRejectReason = ref('')
const chapterRejectBusy = ref(false)

const loadChapters = async () => {
  chapterLoading.value = true
  try {
    const res = await getChapterAuditPage({
      pageNum: chapterPageNum.value,
      pageSize,
      auditStatus: chapterStatus.value
    })
    chapterList.value = res?.list || []
    chapterTotal.value = Number(res?.total || 0)
    selectChapter(chapterList.value[0] || null)
  } finally {
    chapterLoading.value = false
  }
}

const selectChapter = (row) => {
  chapterCurrent.value = row
  chapterRejectOpen.value = false
  chapterRejectReason.value = ''
}

const onChapterStatus = (v) => {
  chapterStatus.value = v === 'all' ? null : Number(v)
  chapterPageNum.value = 1
  loadChapters()
}
const onChapterPage = (p) => {
  chapterPageNum.value = p
  loadChapters()
}

const doChapterPass = async () => {
  if (!chapterCurrent.value) return
  chapterPassBusy.value = true
  try {
    await auditChapterPass(chapterCurrent.value.id)
    ElMessage.success(`《${chapterCurrent.value.novelTitle}》第 ${chapterCurrent.value.chapterNo} 章已通过`)
    loadChapters()
  } finally {
    chapterPassBusy.value = false
  }
}

const doChapterReject = async () => {
  if (!chapterRejectReason.value.trim()) {
    ElMessage.warning('请填写拒绝理由，作者需要知道原因')
    return
  }
  chapterRejectBusy.value = true
  try {
    await auditChapterReject(chapterCurrent.value.id, { reason: chapterRejectReason.value.trim() })
    ElMessage.success('已拒绝并通知作者')
    loadChapters()
  } finally {
    chapterRejectBusy.value = false
  }
}

const onMode = () => {
  if (mode.value === 'novel' && !list.value.length) load()
  if (mode.value === 'chapter' && !chapterList.value.length) loadChapters()
}

onMounted(async () => {
  load()
  loadChapters()
  try {
    categories.value = (await getCategories()) || []
  } catch (e) { /* 忽略 */ }
})

const pendingHint = computed(() =>
  [0, 3, 4].includes(auditStatus.value) ? `${total.value} 条待处理` : `${total.value} 条记录`
)

// 作品信息变更送审：逐项列出「当前生效值 → 修改后」，供管理员对照
const novelDiff = computed(() => {
  const c = current.value
  if (!c || c.auditStatus !== 3) return []
  const pairs = [
    ['书名', c.title, c.pendingTitle],
    ['分类', c.categoryName, c.pendingCategoryName],
    ['笔名', c.author, c.pendingAuthor],
    ['简介', c.intro, c.pendingIntro],
    ['标签', c.tags, c.pendingTags]
  ]
  return pairs.filter(([, from, to]) => (from || '') !== (to || ''))
})

// 封面单独对照：展示两张缩略图，不以文本形式展示图片地址
const coverDiff = computed(() => {
  const c = current.value
  if (!c || c.auditStatus !== 3) return null
  return (c.coverUrl || '') === (c.pendingCoverUrl || '') ? null : c
})

const isChangeReview = computed(() => current.value?.auditStatus === 3)
// 重新上架申请：通过 = 恢复上架；拒绝 = 保持下架（作品本身是合规的）
const isReshelveReview = computed(() => current.value?.auditStatus === 4)
/**
 * 是否需要管理员动作。三种状态均需可操作：首次审核(0)、修改审核中(3)、重新上架审核中(4)。
 * 此处原判断为 `auditStatus === 0`，导致修改审核的条目进入后操作区不渲染，
 * isChangeReview 恒为 false，修改审核实际无法操作。
 */
const isReviewable = computed(() => [0, 3, 4].includes(current.value?.auditStatus))
</script>

<template>
  <div class="audit">
    <div class="toolbar">
      <el-radio-group v-model="mode" size="small" @change="onMode">
        <el-radio-button value="novel">作品审核</el-radio-button>
        <el-radio-button value="chapter">章节审核</el-radio-button>
      </el-radio-group>

      <template v-if="mode === 'novel'">
        <el-radio-group :model-value="auditStatus" size="small" @change="onStatus">
          <el-radio-button :value="0">待审核</el-radio-button>
          <el-radio-button :value="3">修改审核中</el-radio-button>
          <el-radio-button :value="4">重新上架</el-radio-button>
          <el-radio-button :value="1">已通过</el-radio-button>
          <el-radio-button :value="2">已拒绝</el-radio-button>
        </el-radio-group>
        <span class="hint">{{ pendingHint }}</span>
        <div class="toolbar-right">
          <span v-if="checkedIds.length" class="hint">已选 {{ checkedIds.length }} 项</span>
          <el-button
            v-if="[0, 3, 4].includes(auditStatus)"
            type="primary"
            size="small"
            :disabled="!checkedIds.length"
            :loading="passBusy"
            @click="batchPass"
          >批量通过</el-button>
        </div>
      </template>

      <template v-else>
        <el-radio-group :model-value="chapterStatus === null ? 'all' : String(chapterStatus)" size="small" @change="onChapterStatus">
          <el-radio-button value="all">全部待审</el-radio-button>
          <el-radio-button value="0">新增章</el-radio-button>
          <el-radio-button value="3">修改章</el-radio-button>
        </el-radio-group>
        <span class="hint">{{ chapterTotal }} 条待处理</span>
      </template>
    </div>

    <div v-if="mode === 'novel'" class="workbench">
      <section class="pane list-pane" v-loading="loading">
        <div class="pane-head">作品列表</div>
        <div class="list-body">
          <div v-if="!list.length && !loading" class="pane-empty">没有符合条件的作品</div>
          <div
            v-for="row in list"
            :key="row.id"
            class="lrow"
            :class="{ active: current?.id === row.id }"
            @click="select(row)"
          >
            <el-checkbox
              v-if="row.auditStatus === 0"
              class="lrow-check"
              :model-value="checkedIds.includes(row.id)"
              @click.stop
              @change="(v) => toggleCheck(row.id, v)"
            />
            <div class="lrow-main">
              <div class="lrow-title">{{ row.title }}</div>
              <div class="lrow-sub">{{ row.author || '未知作者' }} · {{ fmtTime(row.createTime) }}</div>
            </div>
            <el-tag :type="auditMap[row.auditStatus]?.type || 'info'" size="small" effect="plain">
              {{ auditMap[row.auditStatus]?.text || '未知' }}
            </el-tag>
          </div>
        </div>
        <div class="pane-foot">
          <el-pagination
            small
            layout="prev, pager, next"
            :total="total"
            :page-size="pageSize"
            :current-page="pageNum"
            @current-change="onPage"
          />
        </div>
      </section>

      <section class="pane detail-pane">
        <template v-if="current">
          <div class="d-head">
            <div class="d-cover" :style="posterVars(current)">
              <img v-if="current.coverUrl" :src="current.coverUrl" alt="" />
              <span v-else>{{ posterChar(current.title) }}</span>
            </div>
            <div class="d-meta">
              <div class="d-title">{{ current.title }}</div>
              <div class="d-line">作者：{{ current.author || '未知' }}</div>
              <div class="d-line">提交：{{ fmtTime(current.createTime) }}</div>
              <div class="d-line d-cat">
                <span>分类：</span>
                <el-select
                  v-model="passCategoryId"
                  size="small"
                  style="width: 170px"
                  :disabled="current.auditStatus !== 0"
                >
                  <el-option
                    v-for="c in categories"
                    :key="c.id"
                    :label="c.name + (c.id === current.categoryId ? '（当前）' : '')"
                    :value="c.id"
                  />
                </el-select>
              </div>
            </div>
          </div>

          <div class="d-block">
            <div class="d-label">简介</div>
            <p class="d-intro">{{ current.intro || '（无简介）' }}</p>
          </div>

          <div class="d-block" v-if="novelDiff.length || coverDiff">
            <div class="d-label new">本次修改（当前内容 → 修改后）</div>
            <div class="diff">
              <div v-for="[label, from, to] in novelDiff" :key="label" class="diff-row">
                <span class="diff-label">{{ label }}</span>
                <span class="diff-from">{{ from || '—' }}</span>
                <span class="diff-arrow">→</span>
                <span class="diff-to">{{ to || '—' }}</span>
              </div>
              <div v-if="coverDiff" class="diff-row">
                <span class="diff-label">封面</span>
                <span class="diff-cover">
                  <img v-if="coverDiff.coverUrl" :src="coverDiff.coverUrl" alt="当前封面" />
                  <span v-else>—</span>
                </span>
                <span class="diff-arrow">→</span>
                <span class="diff-cover">
                  <img v-if="coverDiff.pendingCoverUrl" :src="coverDiff.pendingCoverUrl" alt="修改后封面" />
                  <span v-else>—</span>
                </span>
              </div>
            </div>
            <div class="diff-note">审核期间读者看到的仍是左列内容；通过后自动替换为右列。</div>
          </div>

          <div class="d-block" v-if="current.excerpt">
            <div class="d-label">首章正文节选</div>
            <div class="d-text">{{ current.excerpt }}</div>
          </div>

          <div class="d-actions" v-if="isReviewable">
            <template v-if="!rejectOpen">
              <el-button type="primary" :loading="passBusy" @click="doPass">
                {{ isChangeReview ? '通过修改' : isReshelveReview ? '通过并恢复上架' : '通过并上架' }}
              </el-button>
              <el-button type="danger" plain @click="rejectOpen = true">拒绝</el-button>
              <span class="d-hint">
                {{ isChangeReview ? '通过后新内容立即生效'
                  : isReshelveReview ? '通过后恢复分发；拒绝则保持下架'
                  : '通过后自动上架并以站内信通知作者' }}
              </span>
            </template>
            <template v-else>
              <el-input
                v-model="rejectReason"
                type="textarea"
                :rows="3"
                maxlength="200"
                show-word-limit
                placeholder="拒绝理由（必填，将以站内信通知作者）如：内容质量不达标 / 正文与分类不符…"
              />
              <div class="d-reject-row">
                <el-button @click="rejectOpen = false; rejectReason = ''">取消</el-button>
                <el-button type="danger" :loading="rejectBusy" @click="doReject">确认拒绝</el-button>
              </div>
            </template>
          </div>
          <div class="d-actions done" v-else>
            已于此前处理：{{ auditMap[current.auditStatus]?.text }}
            <template v-if="current.auditResult"> · 审核意见：{{ current.auditResult }}</template>
          </div>
        </template>
        <div v-else class="pane-empty big">左侧没有可处理的作品</div>
      </section>
    </div>

    <div v-else class="workbench">
      <section class="pane list-pane" v-loading="chapterLoading">
        <div class="pane-head">章节列表</div>
        <div class="list-body">
          <div v-if="!chapterList.length && !chapterLoading" class="pane-empty">没有符合条件的章节</div>
          <div
            v-for="row in chapterList"
            :key="row.id"
            class="lrow"
            :class="{ active: chapterCurrent?.id === row.id }"
            @click="selectChapter(row)"
          >
            <div class="lrow-main">
              <div class="lrow-title">{{ row.novelTitle }}</div>
              <div class="lrow-sub">第 {{ row.chapterNo }} 章 · {{ row.title }}</div>
            </div>
            <el-tag :type="chapterAuditMap[row.auditStatus]?.type || 'info'" size="small" effect="plain">
              {{ chapterAuditMap[row.auditStatus]?.text || '未知' }}
            </el-tag>
          </div>
        </div>
        <div class="pane-foot">
          <el-pagination
            small
            layout="prev, pager, next"
            :total="chapterTotal"
            :page-size="pageSize"
            :current-page="chapterPageNum"
            @current-change="onChapterPage"
          />
        </div>
      </section>

      <section class="pane detail-pane">
        <template v-if="chapterCurrent">
          <div class="d-head">
            <div class="d-meta">
              <div class="d-title">{{ chapterCurrent.novelTitle }}</div>
              <div class="d-line">第 {{ chapterCurrent.chapterNo }} 章 · {{ chapterCurrent.title }}</div>
              <div class="d-line">
                作者：{{ chapterCurrent.author || '未知' }}　解锁：{{ chapterCurrent.unlockCoin ? chapterCurrent.unlockCoin + ' 币' : '免费' }}
              </div>
              <div class="d-line">
                <el-tag :type="chapterAuditMap[chapterCurrent.auditStatus]?.type || 'info'" size="small" effect="plain">
                  {{ chapterAuditMap[chapterCurrent.auditStatus]?.text || '未知' }}
                </el-tag>
              </div>
            </div>
          </div>

          <div class="d-block" v-if="chapterCurrent.oldExcerpt">
            <div class="d-label old">旧版（读者当前可见）</div>
            <div class="d-text">{{ chapterCurrent.oldExcerpt }}</div>
          </div>
          <div class="d-block">
            <div class="d-label" :class="{ new: chapterCurrent.oldExcerpt }">
              {{ chapterCurrent.oldExcerpt ? '新版（修改后）' : '正文节选' }}
            </div>
            <div class="d-text">{{ chapterCurrent.excerpt }}</div>
          </div>

          <div class="d-actions">
            <template v-if="!chapterRejectOpen">
              <el-button type="primary" :loading="chapterPassBusy" @click="doChapterPass">通过</el-button>
              <el-button type="danger" plain @click="chapterRejectOpen = true">拒绝</el-button>
              <span class="d-hint">通过后新章上架、修改章自动更新</span>
            </template>
            <template v-else>
              <el-input
                v-model="chapterRejectReason"
                type="textarea"
                :rows="3"
                maxlength="200"
                show-word-limit
                placeholder="拒绝理由（必填，将以站内信通知作者）如：正文涉敏 / 内容与标题不符…"
              />
              <div class="d-reject-row">
                <el-button @click="chapterRejectOpen = false; chapterRejectReason = ''">取消</el-button>
                <el-button type="danger" :loading="chapterRejectBusy" @click="doChapterReject">确认拒绝</el-button>
              </div>
            </template>
          </div>
        </template>
        <div v-else class="pane-empty big">左侧没有可处理的章节</div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.audit { display: flex; flex-direction: column; gap: 14px; }

.toolbar {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
}
.toolbar-right { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.hint { font-size: 12.5px; color: var(--muted); }

.workbench {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
.pane {
  background: var(--paper-2);
  border: 1px solid var(--line);
  border-radius: 10px;
  display: flex; flex-direction: column;
  overflow: hidden;
}
.pane-head {
  padding: 10px 14px;
  font-size: 13px; color: var(--muted);
  border-bottom: 1px solid var(--line-soft);
  background: var(--paper);
}
.pane-foot {
  padding: 8px 10px;
  border-top: 1px solid var(--line-soft);
  display: flex; justify-content: center;
}
.list-body { max-height: calc(100vh - 300px); min-height: 220px; overflow-y: auto; }
.pane-empty { padding: 40px 16px; text-align: center; color: var(--muted); font-size: 13px; }
.pane-empty.big { padding: 90px 16px; }

.lrow {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--line-soft);
  cursor: pointer;
  transition: background .15s;
}
.lrow:last-child { border-bottom: none; }
.lrow:hover { background: var(--paper); }
.lrow.active { background: var(--cinnabar-bg); }
.lrow-check { flex-shrink: 0; }
.lrow-main { flex: 1; min-width: 0; }
.lrow-title {
  font-size: 13.5px; color: var(--ink);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.lrow-sub {
  font-size: 12px; color: var(--muted); margin-top: 3px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.detail-pane { padding: 18px 20px 20px; }
.d-head { display: flex; gap: 16px; }
.d-cover {
  width: 84px; height: 112px; border-radius: 6px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  color: rgba(255, 255, 255, 0.92); overflow: hidden; position: relative;
  background: linear-gradient(160deg, var(--pf, #2b2733) 0%, var(--pt, #14121a) 100%);
}
.d-cover img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.d-cover span { font-family: var(--serif); font-size: 30px; font-weight: 600; }
.d-meta { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
.d-title { font-family: var(--serif); font-size: 18px; font-weight: 600; color: var(--ink); }
.d-line { font-size: 13px; color: var(--muted); display: flex; align-items: center; gap: 6px; }

.d-block { margin-top: 16px; }
.d-label { font-size: 12.5px; color: var(--muted); margin-bottom: 6px; }
.d-label.old { color: #8a6a20; }
.d-label.new { color: var(--cinnabar); }
.d-intro { font-size: 13.5px; color: var(--ink-2); line-height: 1.8; }
.d-text {
  font-size: 13.5px; color: var(--ink-2); line-height: 1.9;
  white-space: pre-wrap; word-break: break-word;
  background: var(--paper); border: 1px solid var(--line-soft);
  border-radius: 8px; padding: 14px 16px;
  max-height: 34vh; overflow-y: auto;
}

.d-actions {
  margin-top: 18px; padding-top: 16px;
  border-top: 1px solid var(--line-soft);
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
}
.d-actions.done { font-size: 13px; color: var(--muted); }
.d-reject-row { display: flex; gap: 10px; margin-left: auto; }
.d-hint { font-size: 12.5px; color: var(--muted); }

/* 变更送审的新旧对照 */
.diff {
  border: 1px solid var(--line-soft); border-radius: 8px;
  padding: 12px 14px; background: var(--paper);
}
.diff-row {
  display: grid; grid-template-columns: 48px 1fr 16px 1fr;
  gap: 8px; align-items: start;
  font-size: 12.5px; padding: 4px 0;
}
.diff-label { color: var(--muted); }
.diff-from { color: var(--muted); text-decoration: line-through; word-break: break-word; }
.diff-arrow { color: var(--line); text-align: center; }
.diff-to { color: var(--cinnabar); word-break: break-word; }
.diff-cover {
  width: 48px; height: 64px; border-radius: 4px; overflow: hidden;
  border: 1px solid var(--line); background: var(--paper);
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; color: var(--muted);
}
.diff-cover img { width: 100%; height: 100%; object-fit: cover; }
.diff-note { margin-top: 8px; font-size: 11.5px; color: var(--muted); }

@media (max-width: 1100px) {
  .workbench { grid-template-columns: 1fr; }
  .list-body { max-height: 300px; }
}
</style>
