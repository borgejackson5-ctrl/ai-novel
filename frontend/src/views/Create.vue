<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { aiGenerate, getCategories, publishNovel, importParseNovel, generateCover, uploadCover } from '../api'
import { useUserStore } from '../store/user'
import { createFrameDecoder } from '../utils/sseFrames'
// 流式失败分层与 utils/aiStream.js 共用一份（续写/润色那条链路）
import {
  AiStreamError,
  FailureKind,
  fromFetchError,
  fromHttpStatus,
  fromStreamBreak
} from '../utils/streamError'

const router = useRouter()
const userStore = useUserStore()

const categories = ref([])
const form = ref({ title: '', intro: '', categoryId: null, tags: '', author: '', coverUrl: '' })
const chapters = ref([{ title: '', content: '', unlockCoin: 0 }])
const publishing = ref(false)

const loadCategories = async () => {
  try {
    categories.value = (await getCategories()) || []
  } catch (e) {
    /* 首页已加载过，忽略 */
  }
}
loadCategories()

// ===== 章节动态列表（折叠目录：默认折叠，点开才渲染编辑区） =====
const expandedIndex = ref(0) // 手风琴式：同一时刻只展开一章，DOM 只挂一个 textarea，长目录不卡顿
const batchPrice = ref(5)

// ===== 章节前端分页：本地 chapters 可达数百章，全量渲染 DOM 会卡顿 =====
const chapterPageSize = 50
const chapterPageNum = ref(1)
const pageChapters = computed(() => {
  const start = (chapterPageNum.value - 1) * chapterPageSize
  return chapters.value.slice(start, start + chapterPageSize)
})
// 局部下标 → 全局下标（toggle/remove/expandedIndex 均按全局下标，翻页后仍能定位真实章节）
const globalIndex = (i) => (chapterPageNum.value - 1) * chapterPageSize + i
const pageCount = computed(() => Math.max(1, Math.ceil(chapters.value.length / chapterPageSize)))
// 删除末尾章节导致当前页超界时，自动回退到最后一页
watch(pageCount, (n) => {
  if (chapterPageNum.value > n) chapterPageNum.value = n
})
const onChapterPageChange = (page) => {
  chapterPageNum.value = page
  expandedIndex.value = -1 // 翻页后折叠，避免展开态残留到另一页
}
const toggleChapter = (i) => {
  expandedIndex.value = expandedIndex.value === i ? -1 : i
}
const collapseAll = () => {
  expandedIndex.value = -1
}
const addChapter = () => {
  chapters.value.push({ title: '', content: '', unlockCoin: 5 })
  chapterPageNum.value = Math.ceil(chapters.value.length / chapterPageSize) // 跳到新章节所在页
  expandedIndex.value = chapters.value.length - 1 // 新增后自动展开便于立即编辑
}
const removeChapter = (i) => {
  if (chapters.value.length <= 1) {
    ElMessage.warning('至少保留一章')
    return
  }
  chapters.value.splice(i, 1)
  // 删除后保持手风琴状态稳定：删的是展开章则折叠，删的是它前面的章则下标前移
  if (expandedIndex.value === i) expandedIndex.value = -1
  else if (expandedIndex.value > i) expandedIndex.value -= 1
}
const applyBatchPrice = () => {
  chapters.value.forEach((c, i) => {
    if (i > 0) c.unlockCoin = Number(batchPrice.value || 0)
  })
  ElMessage.success('已应用到全部付费章')
}
const wordCount = (c) => (c.content || '').trim().length
const totalWords = () => chapters.value.reduce((s, c) => s + wordCount(c), 0)

// ===== 从 TXT 导入章节（解析后逐章可编辑，走既有 publish 审核链） =====
const importingTxt = ref(false)
const onTxtChange = async (uploadFile) => {
  const file = uploadFile.raw
  if (!file) return
  if (!/\.txt$/i.test(file.name)) {
    ElMessage.warning('仅支持 TXT 文件')
    return
  }
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 20MB')
    return
  }
  importingTxt.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    const list = await importParseNovel(fd)
    if (!list || !list.length) {
      ElMessage.warning('未识别到章节，请检查 TXT 内容')
      return
    }
    chapters.value = list.map((c) => ({
      title: c.title || '',
      content: c.content || '',
      unlockCoin: Number(c.unlockCoin || 0)
    }))
    expandedIndex.value = -1 // 导入后全部折叠，避免上百章 textarea 撑爆 DOM
    chapterPageNum.value = 1 // 导入后回到第一页
    if (!form.value.title.trim()) {
      form.value.title = (file.name || '').replace(/\.txt$/i, '')
    }
    ElMessage.success(`已解析 ${list.length} 章，可展开逐章编辑后发布`)
  } catch (e) {
    /* 错误已在拦截器提示 */
  } finally {
    importingTxt.value = false
  }
}

// ===== AI 辅助创作（折叠面板） =====
const aiPanelOpen = ref('')
const naming = ref(false)
const aiIntroLoading = ref(false)

// AI 起书名：取生成结果第一行
const aiName = async () => {
  naming.value = true
  try {
    const text = await aiGenerate({ type: 'TITLE', input: form.value.tags || form.value.title || '仙侠修真' })
    const first = String(text || '').split('\n').map((s) => s.trim()).filter(Boolean)[0]
    if (first) form.value.title = first
  } finally {
    naming.value = false
  }
}

// SSE 流式生成简介：打字机效果直接写入表单
const aiIntro = async () => {
  const seed = form.value.title || form.value.tags || '仙侠修真'
  aiIntroLoading.value = true
  let acc = ''
  form.value.intro = ''
  try {
    const token = userStore.token
    const url = `/api/ai/generate/stream?type=INTRO&input=${encodeURIComponent(seed)}`
    let response
    try {
      response = await fetch(url, {
        headers: { Authorization: token }
      })
    } catch (e) {
      // fetch 自身抛错表示未连接成功：原样抛出时用户会看到英文的「Failed to fetch」
      throw fromFetchError(e)
    }
    if (!response.ok) {
      // 额度耗尽 / 入参不合法 / 限流：后端返回 JSON { code, msg }，读出具体提示
      let payload = null
      try {
        payload = await response.json()
      } catch (_) { /* 非 JSON 响应体，交给分层函数兜底 */ }
      throw fromHttpStatus(response.status, payload)
    }
    if (!response.body) {
      throw new AiStreamError(FailureKind.SERVER, '服务器没有返回内容，请稍后重试', { status: response.status })
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    // 帧解析使用 utils/sseFrames.js（与续写/润色共用同一实现）。
    // 此处原为手写 `acc += ev.slice(5).trim()`，缺少 JSON.parse。
    // 服务端每段内容为 JSON 字符串字面量（帧内不能出现真实换行），因此生成的简介
    // 会带有一对引号、段落换行显示为字面量 \n，且不抛异常。
    const frames = createFrameDecoder()
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        for (const chunk of frames.feed(decoder.decode(value, { stream: true }))) {
          acc += chunk
          form.value.intro = acc
        }
      }
    } catch (e) {
      // 流已建立（HTTP 200），中途中断仅在此层可见。
      // 已写入 form 的内容有意不清理：该部分是用户已获得的内容
      throw fromStreamBreak(acc.length)
    }
    ElMessage.success('简介生成完成，可直接修改')
  } catch (e) {
    // 中断使用 warning：并非失败，只是未生成完，已生成的部分仍然保留
    if (e?.kind === FailureKind.INTERRUPTED) {
      ElMessage.warning(e.message)
    } else {
      // 网络错 / 服务端错 / 业务错的文案在分层函数里已经写好（业务错使用后端返回的原文）
      ElMessage.error(e?.message || '生成失败，请稍后重试')
    }
  } finally {
    aiIntroLoading.value = false
  }
}

// ===== 封面：AI 生成 / 自行上传（返回 OSS 永久 URL） =====
const coverGenerating = ref(false)
const coverRightsConfirmed = ref(false)

const generateCoverImg = async () => {
  const seed = `${(form.value.title || '').trim()} ${(form.value.tags || '').trim()}`.trim()
  if (!seed) {
    ElMessage.warning('请先填写书名或标签，便于生成封面')
    return
  }
  coverGenerating.value = true
  try {
    form.value.coverUrl = await generateCover(seed)
    ElMessage.success('封面生成成功，可重新生成或改用上传')
  } catch (e) {
    /* 错误已在拦截器提示 */
  } finally {
    coverGenerating.value = false
  }
}

const onCoverUpload = async (uploadFile) => {
  const file = uploadFile.raw
  if (!file) return
  if (!coverRightsConfirmed.value) {
    ElMessage.warning('请先勾选「确认拥有该图版权或使用权」')
    return
  }
  const fd = new FormData()
  fd.append('file', file)
  try {
    form.value.coverUrl = await uploadCover(fd)
    ElMessage.success('封面上传成功')
  } catch (e) {
    /* 错误已在拦截器提示 */
  }
}

const publish = async () => {
  if (!form.value.title.trim()) {
    ElMessage.warning('请填写书名')
    return
  }
  if (!form.value.categoryId) {
    ElMessage.warning('请选择分类')
    return
  }
  const valid = chapters.value.filter((c) => c.content && c.content.trim())
  if (!valid.length) {
    ElMessage.warning('请至少填写一章正文')
    return
  }
  publishing.value = true
  try {
    await publishNovel({
      title: form.value.title.trim(),
      intro: form.value.intro.trim(),
      categoryId: form.value.categoryId,
      tags: form.value.tags.trim(),
      author: form.value.author.trim(),
      coverUrl: form.value.coverUrl.trim(),
      chapters: valid.map((c, i) => ({
        title: c.title.trim() || `第${i + 1}章`,
        content: c.content,
        unlockCoin: i === 0 ? 0 : Number(c.unlockCoin || 0)
      }))
    })
    ElMessage.success('作品已提交，等待审核，审核结果将第一时间通知你')
    router.push('/my-works')
  } finally {
    publishing.value = false
  }
}
</script>

<template>
  <div class="create-page">
    <div class="hero">
      <h1>创作发布</h1>
      <p>填写书籍信息 → 录入章节正文 → 提交审核，通过后全站可读 · AI 起名/简介仅作辅助</p>
    </div>

    <div class="grid">
      <el-card shadow="never" class="form-card">
        <template #header>
          <div class="card-head">
            <span>① 书籍信息</span>
          </div>
        </template>

        <el-form label-position="top">
          <el-collapse v-model="aiPanelOpen" class="ai-panel" accordion>
            <el-collapse-item name="ai">
              <template #title>
                <span class="ai-panel-title with-ic"><el-icon><MagicStick /></el-icon>AI 辅助创作</span>
                <span class="ai-panel-tip">书名 / 简介（可选）</span>
              </template>
              <div class="ai-panel-body">
                <div class="ai-row">
                  <span class="ai-label">书名</span>
                  <el-button size="small" :loading="naming" @click="aiName">AI 起名</el-button>
                  <span class="ai-note">据题材生成书名，回填至下方书名框</span>
                </div>
                <div class="ai-row">
                  <span class="ai-label">简介</span>
                  <el-button size="small" :loading="aiIntroLoading" @click="aiIntro">AI 生成（流式）</el-button>
                  <span class="ai-note">生成过程实时写入简介框，可再修改</span>
                </div>
                <div class="ai-tip">未配置 AI Key 时返回的是示例内容 · 可在「AI 设置」中配置</div>
              </div>
            </el-collapse-item>
          </el-collapse>

          <el-form-item label="封面（选填）">
            <div class="cover-zone">
              <div class="cover-preview">
                <img v-if="form.coverUrl" :src="form.coverUrl" alt="封面预览" />
                <span v-else class="cover-empty">暂无封面</span>
              </div>
              <div class="cover-ops">
                <el-button size="small" :loading="coverGenerating" @click="generateCoverImg"><el-icon><MagicStick /></el-icon><span>AI 生成封面</span></el-button>
                <el-upload
                  :show-file-list="false"
                  accept=".png,.jpg,.jpeg,.webp,.gif"
                  :auto-upload="false"
                  :on-change="onCoverUpload"
                  style="display:inline-block"
                >
                  <el-button size="small" plain>上传封面</el-button>
                </el-upload>
                <el-button v-if="form.coverUrl" link type="danger" size="small" @click="form.coverUrl = ''">移除</el-button>
              </div>
              <el-checkbox v-model="coverRightsConfirmed" size="small" class="cover-rights">确认拥有所上传图片的版权或使用权（AI 生成无需勾选）</el-checkbox>
              <div class="cover-tip">AI 生成按「书名 + 标签」出图；上传支持 png/jpg/webp/gif，≤5MB</div>
            </div>
          </el-form-item>

          <el-form-item required>
            <template #label>书名</template>
            <el-input v-model="form.title" maxlength="50" show-word-limit placeholder="给你的作品起个响亮的书名" />
          </el-form-item>

          <el-form-item label="简介">
            <el-input
              v-model="form.intro"
              type="textarea"
              :rows="3"
              maxlength="1000"
              show-word-limit
              placeholder="一两句话介绍故事，留悬念更吸引人（选填）"
            />
          </el-form-item>

          <el-form-item label="分类" required>
            <el-select v-model="form.categoryId" placeholder="选择作品分类" style="width: 100%">
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <div class="field-tip">管理员审核时可能会帮你修正到更合适的分类</div>
          </el-form-item>

          <el-form-item label="作者笔名">
            <el-input v-model="form.author" maxlength="50" placeholder="留空则使用你的昵称（选填）" />
          </el-form-item>

          <el-form-item label="标签">
            <el-input v-model="form.tags" maxlength="200" placeholder="多个标签用逗号分隔，如：重生,逆袭,爽文（选填）" />
          </el-form-item>
        </el-form>
      </el-card>

      <el-card shadow="never" class="chapter-card">
        <template #header>
          <div class="card-head">
            <span>② 章节正文</span>
            <div class="head-ops">
              <span class="tip">共 {{ chapters.length }} 章 · {{ totalWords() }} 字 · 首章免费</span>
              <span v-if="chapters.length > chapterPageSize" class="page-hint">共 {{ pageCount }} 页 · 第 {{ chapterPageNum }} 页 · 每页 {{ chapterPageSize }} 章</span>
              <el-button v-if="chapters.length > 1" link type="primary" size="small" @click="collapseAll">折叠全部</el-button>
            </div>
          </div>
        </template>

        <div class="txt-import">
          <el-upload
            :show-file-list="false"
            accept=".txt"
            :auto-upload="false"
            :disabled="importingTxt"
            :on-change="onTxtChange"
          >
            <el-button :loading="importingTxt" plain size="small"><el-icon><Upload /></el-icon><span>从 TXT 导入章节</span></el-button>
          </el-upload>
          <span class="txt-tip">自动按「第X回/章」分章，导入后可逐章编辑</span>
        </div>

        <div class="batch-price">
          <span class="batch-label with-ic"><el-icon><Coin /></el-icon>批量定价</span>
          <el-input-number v-model="batchPrice" :min="0" :max="999" size="small" controls-position="right" />
          <span class="batch-unit">币/章</span>
          <el-button size="small" @click="applyBatchPrice">应用到全部付费章</el-button>
          <span class="batch-tip">首章永久免费 · 不点应用则各章价格不变</span>
        </div>

        <div v-for="(c, i) in pageChapters" :key="globalIndex(i)" class="chapter-block" :class="{ open: expandedIndex === globalIndex(i) }">
          <div class="chapter-head" @click="toggleChapter(globalIndex(i))">
            <span class="chevron">{{ expandedIndex === globalIndex(i) ? '▾' : '▸' }}</span>
            <span class="chapter-no">第 {{ globalIndex(i) + 1 }} 章</span>
            <span class="chapter-title-summary">{{ c.title || `第${globalIndex(i) + 1}章` }}</span>
            <span v-if="globalIndex(i) === 0" class="free-tag">免费</span>
            <span class="chapter-meta">{{ wordCount(c) }} 字</span>
            <span v-if="globalIndex(i) > 0" class="chapter-meta">{{ Number(c.unlockCoin || 0) }} 币</span>
            <el-button
              link
              type="danger"
              size="small"
              class="del-chapter"
              @click.stop="removeChapter(globalIndex(i))"
            >删除</el-button>
          </div>

          <div v-if="expandedIndex === globalIndex(i)" class="chapter-body">
            <div class="chapter-body-row">
              <el-input
                v-model="c.title"
                class="chapter-title-input"
                maxlength="100"
                :placeholder="`第${globalIndex(i) + 1}章 标题（选填）`"
              />
              <el-input-number
                v-if="globalIndex(i) > 0"
                v-model="c.unlockCoin"
                :min="0"
                :max="999"
                size="small"
                controls-position="right"
                class="price-input"
              />
              <span v-if="globalIndex(i) > 0" class="price-unit">币</span>
            </div>
            <el-input
              v-model="c.content"
              type="textarea"
              :rows="10"
              :placeholder="`在此粘贴第 ${globalIndex(i) + 1} 章正文…（${wordCount(c)} 字）`"
            />
          </div>
        </div>

        <div v-if="chapters.length > chapterPageSize" class="chapter-pager">
          <span class="pager-info">第 {{ chapterPageNum }} / {{ pageCount }} 页 · 每页 {{ chapterPageSize }} 章 · 共 {{ chapters.length }} 章</span>
          <el-pagination
            v-model:current-page="chapterPageNum"
            :page-size="chapterPageSize"
            :total="chapters.length"
            layout="prev, pager, next"
            background
            prev-text="上一页"
            next-text="下一页"
            @current-change="onChapterPageChange"
          />
        </div>

        <el-button class="add-chapter-btn" plain @click="addChapter">+ 添加一章</el-button>

        <div class="publish-footer">
          <div class="publish-warn">
            <span class="with-ic"><el-icon><Bell /></el-icon>发布后进入审核流程：AI 预审 + 管理员人工终审，结果将通过站内信通知你</span>
          </div>
          <el-button
            type="primary"
            size="large"
            class="publish-btn"
            :loading="publishing"
            @click="publish"
          >
            <el-icon><Promotion /></el-icon><span>发布作品</span>
          </el-button>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.create-page {
  max-width: 1200px;
  margin: 0 auto;
}
.hero {
  padding: 24px 30px;
  border-radius: 12px;
  background: var(--paper-2);
  border: 1px solid var(--line);
  color: var(--ink);
  margin-bottom: 22px;
}
.hero h1 {
  font-family: var(--serif);
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 1px;
}
.hero p {
  margin-top: 8px;
  color: var(--muted);
  font-size: 14px;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1.15fr;
  gap: 20px;
  align-items: start;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 700;
}
.tip {
  font-size: 12px;
  color: #b0b3b8;
  font-weight: 400;
}
.field-tip {
  font-size: 12px;
  color: #b0b3b8;
  margin-top: 6px;
}
.ai-panel {
  margin-bottom: 18px;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.ai-panel-title {
  font-weight: 700;
  font-size: 13.5px;
  color: var(--primary);
}
.ai-panel-tip {
  margin-left: 10px;
  font-size: 12px;
  color: #b0b3b8;
  font-weight: 400;
}
.ai-panel-body {
  padding: 4px 6px 10px;
}
.ai-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 0;
}
.ai-label {
  font-size: 13px;
  color: #606266;
  width: 34px;
}
.ai-note {
  font-size: 12px;
  color: #b0b3b8;
}
.ai-tip {
  margin-top: 8px;
  font-size: 11.5px;
  color: #c0c4cc;
}
.cover-zone { display: flex; flex-direction: column; gap: 10px; }
.cover-preview {
  width: 120px; height: 160px; border-radius: 10px; overflow: hidden;
  border: 1px solid var(--line); background: #f6f7fb;
  display: flex; align-items: center; justify-content: center;
}
.cover-preview img { width: 100%; height: 100%; object-fit: cover; }
.cover-empty { font-size: 12px; color: #c0c4cc; }
.cover-ops { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.cover-rights { font-size: 12px; color: #909399; height: auto; }
.cover-tip { font-size: 12px; color: #b0b3b8; }
.chapter-block {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
  margin-bottom: 14px;
  background: #fcfcfe;
}
.txt-import {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 8px 10px;
  border: 1px dashed var(--line);
  border-radius: 10px;
  background: rgba(178, 58, 46, 0.04);
}
.txt-tip {
  font-size: 12px;
  color: #b0b3b8;
}
.chapter-head {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}
.chapter-no {
  font-size: 13px;
  font-weight: 700;
  color: var(--primary);
  flex-shrink: 0;
}
.free-tag {
  font-size: 11px;
  color: #2f9e6e;
  background: rgba(47, 158, 110, 0.12);
  padding: 2px 8px;
  border-radius: 10px;
  flex-shrink: 0;
}
.chapter-title-input {
  flex: 1;
}
.price-input {
  width: 96px;
  flex-shrink: 0;
}
.price-unit {
  font-size: 12px;
  color: var(--muted);
  flex-shrink: 0;
}
.del-chapter {
  flex-shrink: 0;
}
.add-chapter-btn {
  width: 100%;
  margin-bottom: 16px;
  border-style: dashed;
}
.chapter-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 16px;
  padding: 10px 0;
  border-top: 1px solid #f0f0f0;
}
.pager-info {
  font-size: 13px;
  color: #606266;
  font-weight: 600;
}
.page-hint {
  font-size: 12px;
  color: var(--primary);
  font-weight: 600;
}
.publish-warn {
  background: rgba(247, 186, 42, 0.1);
  border: 1px solid rgba(247, 186, 42, 0.35);
  color: #a0741a;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 12.5px;
  margin: 6px 0 16px;
  line-height: 1.7;
}
.publish-btn {
  width: 100%;
  height: 46px;
  font-size: 16px;
  font-weight: 700;
  background: var(--cinnabar);
  border: none;
}
.head-ops {
  display: flex;
  align-items: center;
  gap: 12px;
}
.batch-price {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px 12px;
  margin-bottom: 14px;
  border: 1px dashed #e6d9a8;
  border-radius: 10px;
  background: rgba(247, 186, 42, 0.06);
}
.batch-label {
  font-size: 13px;
  font-weight: 700;
  color: #a0741a;
}
.batch-unit {
  font-size: 12px;
  color: var(--muted);
}
.batch-tip {
  font-size: 11.5px;
  color: #c0a15c;
}
.chevron {
  width: 14px;
  color: var(--primary);
  font-size: 13px;
  flex-shrink: 0;
}
.chapter-title-summary {
  flex: 1;
  font-size: 13.5px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chapter-meta {
  font-size: 12px;
  color: var(--muted);
  flex-shrink: 0;
}
.chapter-block.open {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px rgba(178, 58, 46, 0.08);
}
.chapter-body {
  margin-top: 10px;
}
.chapter-body-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.publish-footer {
  position: sticky;
  bottom: 0;
  z-index: 10;
  background: #fff;
  padding-top: 10px;
  margin-top: 6px;
  border-top: 1px solid #f0f0f0;
}
@media (max-width: 900px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
