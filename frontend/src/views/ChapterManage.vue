<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getNovelDetail,
  getAuthorChapters,
  getChapterContent,
  addChapter,
  updateChapter,
  deleteChapter,
  reviewChapter,
  aiContinueStream,
  aiPolishStream
} from '../api'
import { fmtWan } from '../utils/cover'
// 流式失败分层（与 utils/aiStream.js 同一份）：用来区分「网络错 / 服务端错 / 业务错 / 中途断」
import { FailureKind } from '../utils/streamError'
import WorkbenchTabs from '../components/WorkbenchTabs.vue'
import { CircleCheck, CircleClose, Clock, InfoFilled, Refresh } from '@element-plus/icons-vue'

const route = useRoute()
const novelId = route.params.id

const loading = ref(true)
const novel = ref(null)
const list = ref([])
const pageNum = ref(1)
const pageSize = 20
const total = ref(0)

// 已完结的作品章节全部锁定（服务端也会拦，这里只是把入口关掉）
const finished = computed(() => novel.value?.serialStatus === 1)

// 章节审核状态
const chapterAuditMap = {
  0: { text: '待审核', type: 'warning', icon: Clock },
  1: { text: '已通过', type: 'success', icon: CircleCheck },
  2: { text: '已拒绝', type: 'danger', icon: CircleClose },
  3: { text: '修改审核中', type: 'warning', icon: Refresh }
}
const auditOf = (s) => chapterAuditMap[s] || { text: '未知', type: 'info', icon: InfoFilled }

const load = async (page = 1) => {
  pageNum.value = page
  loading.value = true
  try {
    const [novelRes, chaptersRes] = await Promise.all([
      getNovelDetail(novelId),
      getAuthorChapters(novelId, { pageNum: page, pageSize })
    ])
    novel.value = novelRes
    list.value = chaptersRes?.list || []
    total.value = Number(chaptersRes?.total || 0)
  } finally {
    loading.value = false
  }
}

// ===== 新增 / 编辑弹窗 =====
const dialog = ref(false)
const editing = ref(false)
const saving = ref(false)
const form = ref({ id: null, chapterNo: null, title: '', content: '', unlockCoin: 0 })
const contentLoading = ref(false)

const openAdd = () => {
  editing.value = false
  form.value = { id: null, chapterNo: null, title: '', content: '', unlockCoin: 0 }
  dialog.value = true
}

const openEdit = async (row) => {
  editing.value = true
  form.value = { id: row.id, chapterNo: row.chapterNo, title: row.title, content: '', unlockCoin: row.unlockCoin ?? 0 }
  dialog.value = true
  contentLoading.value = true
  try {
    const res = await getChapterContent(row.id)
    form.value.content = res?.content || ''
  } catch (e) {
    /* 正文拉取失败不阻塞编辑弹窗 */
  } finally {
    contentLoading.value = false
  }
}

const submit = async () => {
  if (!form.value.content.trim()) {
    ElMessage.warning('章节正文不能为空')
    return
  }
  saving.value = true
  try {
    const payload = {
      title: form.value.title.trim(),
      content: form.value.content,
      unlockCoin: form.value.unlockCoin ?? 0
    }
    if (editing.value) {
      await updateChapter(form.value.id, payload)
      ElMessage.success('已提交审核，通过后无缝替换')
      dialog.value = false
      load(pageNum.value)
    } else {
      await addChapter({ novelId, ...payload })
      ElMessage.success('新章节已提交审核')
      dialog.value = false
      // 新章排在最后：直接跳转到最后一页，否则用户看不到刚添加的章节
      load(Math.max(1, Math.ceil((total.value + 1) / pageSize)))
    }
  } finally {
    saving.value = false
  }
}

// ===== AI 写作（续写 / 润色）=====
// 生成的内容不写入数据库：它只是写入编辑器的一份草稿，作者点「提交」才会入库并走敏感词与送审。
// 这样无需引入「AI 稿」这种新状态，也不会出现 AI 生成内容绕过审核的路径。
const contentRef = ref(null)
const writing = ref(false)
const writingKind = ref('')
const chargedUnits = ref(0)
let writingCtrl = null

const continueDialog = ref(false)
const continueForm = ref({ direction: '', length: 'MEDIUM' })

const polishDialog = ref(false)
const polishSource = ref('')
const polishResult = ref('')
const polishRange = ref({ start: 0, end: 0 })
const polishDone = ref(false)

// 工具栏右侧那行小字：进行中说明当前动作与扣减字数；完成后继续显示消耗（作者需知道消耗明细）；
// 其余时候提示可选中文字进行润色。若完成后不保留显示，该数值会即刻消失，作者只能凭记忆估算
const doneNote = ref('')
const aiNote = computed(() => {
  if (writing.value) {
    const what = writingKind.value === 'continue' ? '正在往下写' : '正在改写'
    return chargedUnits.value ? `${what}（本次 ${chargedUnits.value} 字）` : what + '…'
  }
  return doneNote.value || '选中一段文字，可以润色它'
})

const openContinue = () => {
  if (!form.value.content.trim()) {
    ElMessage.warning('先写点内容，再来续写')
    return
  }
  continueForm.value = { direction: '', length: 'MEDIUM' }
  doneNote.value = ''
  continueDialog.value = true
}

const startContinue = async () => {
  if (!form.value.content.trim()) {
    ElMessage.warning('先写点内容，再来续写')
    return
  }
  continueDialog.value = false
  // 提交的是「续写开始前的正文」：先固定基线，后续追加内容不会影响本次请求
  const base = form.value.content
  writing.value = true
  writingKind.value = 'continue'
  chargedUnits.value = 0
  writingCtrl = new AbortController()
  let appended = false
  const joiner = base.endsWith('\n') ? '' : '\n\n'
  try {
    await aiContinueStream(
      { content: base, direction: continueForm.value.direction, length: continueForm.value.length },
      {
        signal: writingCtrl.signal,
        onUnits: (n) => { chargedUnits.value = n },
        onChunk: (chunk) => {
          // 收到第一段文字后才补分隔换行：请求一开始即被拒（额度不足）时不应在正文中留下两个空行
          if (!appended) {
            form.value.content += joiner
            appended = true
          }
          form.value.content += chunk
        }
      }
    )
    doneNote.value = chargedUnits.value ? `已续写，本次消耗 ${chargedUnits.value} 字` : ''
    ElMessage.success(chargedUnits.value ? `续写完成，本次消耗 ${chargedUnits.value} 字` : '续写完成')
  } catch (e) {
    if (e?.name === 'AbortError') {
      ElMessage.info('已停止。这一段的额度不会退回，已经生成的部分可以继续用')
    } else if (e?.kind === FailureKind.INTERRUPTED) {
      // 已写入正文的内容予以保留，使用 warning 而非 error：并非失败，只是未生成完。
      // 该提示已写明「已生成的部分可以保留」，否则作者会认为内容失效并重试（再次扣减额度）
      ElMessage.warning(e.message)
    } else {
      // 网络错 / 服务端错 / 业务错：文案已在 utils/streamError.js 里按种类写好
      ElMessage.error(e?.message || '续写失败，请稍后重试')
    }
  } finally {
    writing.value = false
    writingKind.value = ''
    writingCtrl = null
  }
}

const startPolish = async (mode) => {
  // 展示的是对照框，作者点击「替换选中的那段」才实际修改，与审查同一原则：是否修改由作者决定。
  // 选区需在调用接口之前读取：弹窗打开后焦点转移，textarea 上的选区随之丢失
  const ta = contentRef.value?.textarea
  if (!ta) return
  const start = ta.selectionStart ?? 0
  const end = ta.selectionEnd ?? 0
  if (end <= start) {
    ElMessage.warning('先在正文里选中要润色的一段文字')
    return
  }
  const text = form.value.content.slice(start, end)
  if (text.trim().length < 20) {
    ElMessage.warning('选中的内容太短了，至少选一段完整的话')
    return
  }
  if (text.length > 2000) {
    ElMessage.warning('选中的内容太长了，一次最多 2000 字，先分段润色')
    return
  }

  doneNote.value = ''
  polishSource.value = text
  polishRange.value = { start, end }
  polishResult.value = ''
  polishDone.value = false
  polishDialog.value = true
  writing.value = true
  writingKind.value = 'polish'
  chargedUnits.value = 0
  writingCtrl = new AbortController()
  try {
    await aiPolishStream(
      { content: text, mode },
      {
        signal: writingCtrl.signal,
        onUnits: (n) => { chargedUnits.value = n },
        onChunk: (chunk) => { polishResult.value += chunk }
      }
    )
    polishDone.value = true
  } catch (e) {
    if (e?.name === 'AbortError') {
      ElMessage.info('已停止。改写没写完，这一版不完整，别直接替换')
    } else if (e?.kind === FailureKind.INTERRUPTED) {
      // 与「已停止」同级：不关闭对照框，已改写出来的部分予以保留，
      // 是否采用由作者判断
      ElMessage.warning(e.message)
    } else {
      ElMessage.error(e?.message || '润色失败，请稍后重试')
      polishDialog.value = false
    }
  } finally {
    writing.value = false
    writingKind.value = ''
    writingCtrl = null
  }
}

const applyPolish = () => {
  const { start, end } = polishRange.value
  const current = form.value.content
  // 替换前校验一次：位置不一致时不执行替换。按旧下标修改已变更的正文，
  // 会改到错误位置，且修改后无法从结果察觉
  if (current.slice(start, end) !== polishSource.value) {
    ElMessage.warning('正文已经改动过了，为免改错位置，请重新选中一段再润色')
    polishDialog.value = false
    return
  }
  form.value.content = current.slice(0, start) + polishResult.value + current.slice(end)
  polishDialog.value = false
  doneNote.value = chargedUnits.value ? `已润色，本次消耗 ${chargedUnits.value} 字` : ''
  ElMessage.success('已替换成改写后的版本')
}

const stopWriting = () => {
  writingCtrl?.abort()
}

// ===== AI 审查（发布前自查：错别字 / 语病 / 标点 / 前后不一致）=====
const reviewDialog = ref(false)
const reviewingId = ref(null)
const reviewRow = ref(null)
const reviewResult = ref(null)

// 问题类型 → 标签配色：错别字优先级最高用红，语病次之用橙，一致性用蓝，标点类用灰
const tagType = (type) => {
  const t = type || ''
  if (t.includes('错别字') || t.includes('用词')) return 'danger'
  if (t.includes('语病')) return 'warning'
  if (t.includes('一致')) return 'primary'
  return 'info'
}

const openReview = async (row) => {
  if (reviewingId.value) return
  reviewingId.value = row.id
  try {
    const res = await reviewChapter(row.id)
    if (!res?.ok) {
      // 「未审查成功」与「无问题」是两种结果：此处需明确区分，否则作者会认为稿件已通过审查
      ElMessage.warning(res?.message || '这次审查没能完成，请稍后再试')
      return
    }
    reviewRow.value = row
    reviewResult.value = res
    reviewDialog.value = true
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    reviewingId.value = null
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认删除第 ${row.chapterNo} 章「${row.title}」？删除后不可恢复。`,
      '删除章节',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch (e) {
    return
  }
  await deleteChapter(row.id)
  ElMessage.success('已删除')
  // 删掉本页最后一条时回退一页，避免停在空页
  load(list.value.length === 1 && pageNum.value > 1 ? pageNum.value - 1 : pageNum.value)
}

onMounted(load)
</script>

<template>
  <div class="manage-page">
    <WorkbenchTabs :novel-id="novelId" active="manage" />
    <div class="head">
      <div class="head-left">
        <h2 class="title">{{ novel?.title || '章节管理' }}</h2>
        <el-tag
          v-if="novel"
          :type="auditOf(novel.auditStatus).type"
          size="small"
          effect="light"
          round
        >
          {{ novel.auditStatus === 1 ? '作品已上架' : '作品审核中' }}
        </el-tag>
        <el-tag
          v-if="novel && novel.serialStatusText"
          :type="finished ? 'info' : ''"
          size="small"
          effect="plain"
          round
        >
          {{ novel.serialStatusText }}
        </el-tag>
      </div>
      <el-tooltip
        :content="finished ? '作品已完结，章节不可修改；如需继续更新，请先到「我的作品」申请恢复连载' : ''"
        :disabled="!finished"
        placement="bottom"
      >
        <span>
          <el-button type="primary" round :disabled="finished" @click="openAdd">＋ 新增章节</el-button>
        </span>
      </el-tooltip>
    </div>

    <div v-if="finished" class="tip locked">
      作品已标记为「已完结」，章节不可新增、修改或删除。如需继续更新，请到「我的作品」申请恢复连载，管理员通过后解锁。
    </div>
    <div v-else class="tip">
      已发布章节的修改需要审核：审核期间读者看到的仍是原内容，通过后自动更新，未通过则保留原内容。
      已发布章节不可删除，仅可修改重审。
    </div>

    <el-table v-loading="loading" :data="list" stripe>
      <el-table-column prop="chapterNo" label="章序" width="80" />
      <el-table-column prop="title" label="章节标题" min-width="180">
        <template #default="{ row }">
          <span class="ch-title">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column label="字数" width="110">
        <template #default="{ row }">{{ fmtWan(row.wordCount) }}</template>
      </el-table-column>
      <el-table-column label="解锁币" width="100">
        <template #default="{ row }">
          <span :class="row.unlockCoin ? 'coin' : 'free'">{{ row.unlockCoin ? row.unlockCoin + ' 币' : '免费' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="审核状态" width="130">
        <template #default="{ row }">
          <el-tag :type="auditOf(row.auditStatus).type" size="small" effect="light" round>
            <el-icon><component :is="auditOf(row.auditStatus).icon" /></el-icon> {{ auditOf(row.auditStatus).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="审核意见" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="opinion">{{ row.auditResult || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-tooltip content="发布前自查：错别字、语病、标点、前后不一致" placement="top">
            <span>
              <el-button
                link
                type="primary"
                size="small"
                :loading="reviewingId === row.id"
                :disabled="!!reviewingId"
                @click="openReview(row)"
              ><el-icon><MagicStick /></el-icon><span>审查</span></el-button>
            </span>
          </el-tooltip>
          <el-tooltip :disabled="!finished" content="作品已完结，章节不可修改" placement="top">
            <span>
              <el-button
                link
                type="primary"
                size="small"
                :disabled="finished"
                @click="openEdit(row)"
              ><el-icon><EditPen /></el-icon><span>编辑</span></el-button>
            </span>
          </el-tooltip>
          <el-tooltip
            :disabled="!finished && row.auditStatus !== 1"
            :content="finished ? '作品已完结，章节不可删除' : '已发布章节不可删除，仅可修改重审'"
            placement="top"
          >
            <span>
              <el-button
                link
                type="danger"
                size="small"
                :disabled="finished || row.auditStatus === 1"
                @click="remove(row)"
              ><el-icon><Delete /></el-icon><span>删除</span></el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-if="total > pageSize"
      class="ch-pager"
      layout="prev, pager, next, total"
      :total="total"
      :page-size="pageSize"
      :current-page="pageNum"
      @current-change="load"
    />

    <div v-if="!list.length && !loading" class="empty">
      <div class="empty-ic"><el-icon><Document /></el-icon></div>
      <div class="empty-t">还没有章节</div>
      <div class="empty-s">点击右上角「新增章节」开始连载</div>
    </div>

    <el-dialog
      v-model="dialog"
      :title="editing ? `编辑第 ${form.chapterNo} 章` : '新增章节'"
      width="680px"
      align-center
      :close-on-click-modal="false"
    >
      <div v-loading="contentLoading">
        <el-form label-width="80px">
          <el-form-item label="章节标题">
            <el-input
              v-model="form.title"
              :placeholder="`第 ${form.chapterNo || 'N'} 章`"
              maxlength="100"
            />
          </el-form-item>
          <el-form-item label="解锁币">
            <el-input-number v-model="form.unlockCoin" :min="0" :max="100000" :disabled="form.chapterNo === 1" />
            <span v-if="form.chapterNo === 1" class="hint">首章强制免费试读</span>
            <span v-else class="hint">0 为免费章，读者无需解锁</span>
          </el-form-item>
          <el-form-item label="正文">
            <div class="writing">
              <div class="ai-bar">
                <el-button size="small" :disabled="writing" @click="openContinue">
                  <el-icon><MagicStick /></el-icon><span>AI 续写</span>
                </el-button>
                <el-dropdown v-if="!writing" trigger="click" @command="startPolish">
                  <el-button size="small">
                    <el-icon><EditPen /></el-icon><span>AI 润色</span>
                    <el-icon class="arrow"><ArrowDown /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="EXPRESS">改通顺（保持原意）</el-dropdown-item>
                      <el-dropdown-item command="COMPACT">精简</el-dropdown-item>
                      <el-dropdown-item command="VIVID">加画面感</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <el-button v-else size="small" @click="stopWriting">停止</el-button>
                <span class="ai-note">{{ aiNote }}</span>
              </div>
              <el-input
                ref="contentRef"
                v-model="form.content"
                type="textarea"
                :rows="14"
                placeholder="在这里写正文…"
              />
            </div>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">
          {{ editing ? '提交修改（送审）' : '提交新章（送审）' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="continueDialog"
      title="AI 续写"
      width="480px"
      align-center
      :close-on-click-modal="false"
    >
      <el-form label-width="84px">
        <el-form-item label="写多长">
          <el-radio-group v-model="continueForm.length">
            <el-radio-button value="SHORT">短</el-radio-button>
            <el-radio-button value="MEDIUM">中</el-radio-button>
            <el-radio-button value="LONG">长</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="想写什么">
          <el-input
            v-model="continueForm.direction"
            maxlength="200"
            placeholder="可以不填。例如：主角发现了那条线索"
          />
        </el-form-item>
      </el-form>
      <div class="ai-cost">
        会取本章末尾一段正文作为上文，按送进去的字数扣免费额度，不按整章算。
        续写出来的只是草稿，直接改就行；点提交才会入库。
      </div>
      <template #footer>
        <el-button @click="continueDialog = false">取消</el-button>
        <el-button type="primary" @click="startContinue">开始续写</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="polishDialog"
      title="AI 润色"
      width="640px"
      align-center
      :close-on-click-modal="false"
    >
      <div class="polish">
        <div class="polish-label">原文</div>
        <div class="polish-text source">{{ polishSource }}</div>
        <div class="polish-label">改写</div>
        <div class="polish-text result" :class="{ dim: !polishDone }">
          {{ polishResult }}<span v-if="writingKind === 'polish'" class="caret"></span>
        </div>
        <div v-if="polishDone && chargedUnits" class="ai-cost small">
          本次消耗 {{ chargedUnits }} 字
        </div>
        <div v-else-if="!writing && !polishDone" class="ai-cost small">
          这次没写完，这一版不能直接用
        </div>
      </div>
      <template #footer>
        <el-button v-if="writingKind === 'polish'" @click="stopWriting">停止</el-button>
        <el-button v-else @click="polishDialog = false">丢弃</el-button>
        <el-button type="primary" :disabled="writing || !polishDone" @click="applyPolish">
          替换选中的那段
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewDialog" title="AI 审查结果" width="640px">
      <div v-if="reviewResult" class="review">
        <div class="review-head">
          <span class="review-chapter">
            第 {{ reviewRow?.chapterNo }} 章 {{ reviewRow?.title || '' }}
          </span>
          <span class="review-meta">本次审查 {{ reviewResult.reviewedChars }} 字</span>
        </div>
        <div class="review-summary">{{ reviewResult.summary || '审查完成' }}</div>
        <div v-if="reviewResult.issues?.length" class="review-list">
          <div v-for="(it, i) in reviewResult.issues" :key="i" class="review-item">
            <el-tag size="small" :type="tagType(it.type)">{{ it.type || '问题' }}</el-tag>
            <div class="review-body">
              <div class="review-excerpt">{{ it.excerpt }}</div>
              <div class="review-suggestion">{{ it.suggestion }}</div>
            </div>
          </div>
        </div>
        <el-empty v-else description="没有发现问题" :image-size="70" />
      </div>
      <template #footer>
        <el-button type="primary" @click="reviewDialog = false">知道了</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.manage-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 8px 0 40px;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.head-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.title {
  font-size: 20px;
  font-weight: 800;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}
.tip {
  font-size: 12.5px;
  color: #7c818e;
  background: #f6f4ff;
  border-radius: 10px;
  padding: 10px 14px;
  margin-bottom: 14px;
  line-height: 1.7;
}
/* 已完结的锁定提示：用朱砂系，和普通说明区分开 */
.tip.locked {
  color: var(--ink);
  background: var(--cinnabar-bg);
  border: 1px solid rgba(178, 58, 46, 0.18);
}
.ch-title {
  font-weight: 600;
}
.coin {
  color: #d48806;
  font-weight: 600;
}
.free {
  color: #67c23a;
}
.opinion {
  font-size: 12.5px;
  color: #7c818e;
}
.empty {
  padding: 60px 0;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.empty-ic { font-size: 46px; }
.empty-t { font-size: 16px; font-weight: 700; }
.empty-s { color: var(--muted); font-size: 13px; }
.ch-pager { margin-top: 18px; justify-content: flex-end; }
.hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--muted);
}
.review-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.review-chapter { font-weight: 600; }
.review-meta { font-size: 12px; color: #909399; }
.review-summary {
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-2);
  background: var(--paper);
  border-radius: 8px;
}
.review-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 46vh;
  margin-top: 12px;
  overflow: auto;
}
.review-item {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 8px;
}
.review-body { flex: 1; min-width: 0; }
.review-excerpt { font-size: 13.5px; line-height: 1.6; color: var(--ink); word-break: break-all; }
.review-suggestion { margin-top: 4px; font-size: 12.5px; line-height: 1.6; color: #b03a2e; word-break: break-all; }

.writing { width: 100%; }
.ai-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.ai-note { font-size: 12.5px; color: var(--muted); }
.ai-bar .arrow { margin-left: 4px; font-size: 12px; }
.ai-cost {
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--muted);
  background: var(--paper);
  border-radius: 8px;
  padding: 10px 12px;
}
.ai-cost.small { margin-top: 10px; padding: 6px 10px; }
.polish-label { font-size: 12.5px; color: var(--muted); margin-bottom: 6px; }
.polish-text {
  font-size: 13.5px;
  line-height: 1.9;
  color: var(--ink);
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 14px;
  max-height: 220px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.polish-text.source { color: var(--ink-2); }
.polish-text.result { border-color: rgba(178, 58, 46, 0.25); }
.polish-text.result.dim { color: var(--muted); }
.caret {
  display: inline-block;
  width: 6px;
  height: 14px;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--cinnabar);
  animation: caret-blink 1s steps(2, start) infinite;
}
@keyframes caret-blink { to { visibility: hidden; } }
</style>
