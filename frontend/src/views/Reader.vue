<script setup>
import { ref, computed, reactive, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getNovelDetail, getChapter, getChapterPage, getChapterContent, unlockNovel, checkUnlocked,
  recordReadHistory, getChapterComments, addComment, deleteComment, likeComment
} from '../api'
import { useUserStore } from '../store/user'
import { useReaderStore } from '../store/reader'
import { setBookmark, getChapterPos, setChapterPos, KEY_SETTINGS, readLocal, writeLocal } from '../utils/reading'
import { fmtTime } from '../utils/format'
import { pullPreference, pushPreference, pushProgress } from '../utils/readerSync'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const readerStore = useReaderStore()

// ================= 书籍 / 章节数据（解锁链路与既有行为一致，修改需谨慎） =================
const novel = ref({})
const chapter = ref(null)      // 当前章元数据（getChapter(id)）
const content = ref('')
const locked = ref(false)
// 章节彻底加载不出来（作品被删除/下架、章节不存在）：展示兜底页而不是空白
const failed = ref(false)
const loading = ref(false)
const drawer = ref(false)
const prevChapterId = ref(null)
const nextChapterId = ref(null)

// 目录抽屉分页（服务端分页，不再一次性拉全量章节）
const tocList = ref([])
const tocPageNum = ref(1)
const tocPageSize = 50
const tocTotal = ref(0)

const novelId = computed(() => route.params.id)
const chapterId = computed(() => route.params.cid)
const totalChapters = computed(() => Number(novel.value.totalChapters || 0))
const prevChapter = computed(() => (prevChapterId.value ? { id: prevChapterId.value } : null))
const nextChapter = computed(() => (nextChapterId.value ? { id: nextChapterId.value } : null))

// ================= 阅读偏好（按账号隔离的 localStorage 持久化，见 utils/reading.js） =================
const FONT_FAMILIES = {
  song: `'Songti SC','SimSun','STSong','Noto Serif SC',serif`,
  hei: `'PingFang SC','Microsoft YaHei','Heiti SC',sans-serif`,
  kai: `'Kaiti SC','KaiTi','STKaiti','Noto Serif SC',serif`
}
const THEMES = {
  day:   { label: '宣纸', bg: '#fffdf9', fg: '#1c1a17', muted: '#8a7f6d', bar: '#f7f4ed' },
  sepia: { label: '护眼', bg: '#f2e8d0', fg: '#4f3d2c', muted: '#9c8b70', bar: '#e8dbbc' },
  night: { label: '夜间', bg: '#14120f', fg: '#c9c3b8', muted: '#7a7266', bar: '#1f1c17' }
}

function clamp(v, min, max) { return Math.min(max, Math.max(min, v)) }
function num(v, def, min, max) {
  const n = Number(v)
  if (v == null || v === '' || Number.isNaN(n)) return def
  return clamp(n, min, max)
}

function defaultSettings() {
  return {
    fontSize: 18,
    fontFamily: 'song',
    lineHeight: 2.0,
    columnWidth: 680,
    theme: 'day',
    brightness: 1,
    mode: 'scroll',
    autoSpeed: 60 // px / 秒
  }
}

// 把来源（本地 JSON / 云端偏好）归一化合并进 base，做字段级校验防脏数据
function mergeSettings(base, s) {
  base.fontSize = num(s.fontSize, base.fontSize, 14, 28)
  base.fontFamily = FONT_FAMILIES[s.fontFamily] ? s.fontFamily : base.fontFamily
  base.lineHeight = num(s.lineHeight, base.lineHeight, 1.4, 3.2)
  base.columnWidth = num(s.columnWidth, base.columnWidth, 480, 1000)
  base.theme = THEMES[s.theme] ? s.theme : base.theme
  base.brightness = num(s.brightness, 1, 0.3, 1)
  base.mode = s.mode === 'page' ? 'page' : 'scroll'
  base.autoSpeed = num(s.autoSpeed, base.autoSpeed, 10, 240)
  return base
}

function loadSettings() {
  try {
    const s = JSON.parse(readLocal(KEY_SETTINGS) || '{}')
    return mergeSettings(defaultSettings(), s)
  } catch (e) {
    return defaultSettings() // 忽略损坏数据
  }
}

const settings = reactive(loadSettings())
watch(settings, () => {
  writeLocal(KEY_SETTINGS, JSON.stringify(settings))
  pushPreference(settings) // 云同步偏好（内部防抖 + 静默失败）
}, { deep: true })

// 挂载时：云端偏好覆盖本地并应用；无云端记录则把本地默认首推到云端
async function syncPreference() {
  const cloud = await pullPreference()
  if (cloud) mergeSettings(settings, cloud)
  else pushPreference(settings)
}

const theme = computed(() => THEMES[settings.theme])
const fontFamily = computed(() => FONT_FAMILIES[settings.fontFamily])
const fontStyle = computed(() => ({
  fontSize: settings.fontSize + 'px',
  fontFamily: fontFamily.value,
  lineHeight: settings.lineHeight
}))
// 滚动模式正文：居中 + 栏宽约束
const scrollContentStyle = computed(() => ({
  ...fontStyle.value,
  maxWidth: settings.columnWidth + 'px',
  margin: '0 auto'
}))
const dimOpacity = computed(() => +(1 - settings.brightness).toFixed(2))
// 亮度遮罩：沉浸态盖满全屏，普通态只遮正文区（避开 62px 顶栏）
const dimMaskStyle = computed(() => ({
  opacity: dimOpacity.value,
  top: readerStore.immersive ? '0px' : '62px',
  zIndex: readerStore.immersive ? 400 : 50
}))

const rootStyle = computed(() => ({
  '--reader-bg': theme.value.bg,
  '--reader-fg': theme.value.fg,
  '--reader-muted': theme.value.muted,
  '--reader-bar': theme.value.bar
}))

// ================= 加载章节（元数据/正文分离 + 上/下一章导航） =================
const load = async () => {
  loading.value = true
  locked.value = false
  failed.value = false
  content.value = ''
  chapter.value = null
  prevChapterId.value = null
  nextChapterId.value = null
  try {
    if (!novel.value.id) novel.value = await getNovelDetail(novelId.value)

    // 当前章元数据（title/unlockCoin 等）。付费章正文接口会 403，故先取元数据判断是否锁定
    chapter.value = await getChapter(chapterId.value)

    const free = (chapter.value.unlockCoin || 0) === 0
    const unlocked = free
      || (await checkUnlocked(novelId.value, chapterId.value))
      || (await checkUnlocked(novelId.value, null))
    if (!unlocked) {
      locked.value = true
      return
    }
    const data = await getChapterContent(chapterId.value)
    content.value = data.content || ''
    // 正文接口带回上/下一章 ID，供翻页导航（不再为导航拉全量目录）
    prevChapterId.value = data.prevChapterId || null
    nextChapterId.value = data.nextChapterId || null
    // 记录阅读历史（追加写，失败静默，不阻塞阅读）
    recordReadHistory({
      novelId: novelId.value,
      chapterId: chapterId.value,
      chapterNo: chapter.value.chapterNo,
      novelTitle: novel.value.title || '',
      chapterTitle: chapter.value.title || ''
    }).catch(() => {})
    await nextTick()
    restorePosition()
    if (settings.mode === 'page') computePages()
  } catch (e) {
    // 作品被删除 / 下架，或章节不存在：兜住页面状态给出可操作提示，而不是停在空白页。
    // 具体原因（404 / 403）由请求拦截器负责提示。
    failed.value = true
  } finally {
    loading.value = false
  }
}

// ================= 目录抽屉分页 =================
const loadToc = async (pageNum = 1) => {
  try {
    const data = await getChapterPage(novelId.value, { pageNum, pageSize: tocPageSize })
    tocList.value = data.list || []
    tocTotal.value = Number(data.total) || 0
    tocPageNum.value = pageNum
  } catch (e) { /* 拦截器已提示 */ }
}

// 打开目录：定位到当前章所在页（避免当前章不在第一页时高亮丢失）
const openToc = () => {
  const no = chapter.value?.chapterNo
  loadToc(no ? Math.ceil(no / tocPageSize) : 1)
}

// ================= 本章说（章评） =================
// 与书评是两个维度：书评评整本，章评评当前这一章，读者是在「读到这里」的地方说话的
const commentsOpen = ref(false)
const comments = ref([])
const commentTotal = ref(0)
const commentPage = ref(1)
const commentLoading = ref(false)
const commentText = ref('')

const loadComments = async (reset = true) => {
  if (!chapter.value) return
  commentLoading.value = true
  try {
    if (reset) commentPage.value = 1
    const data = await getChapterComments(chapter.value.id, {
      pageNum: commentPage.value,
      pageSize: 10
    })
    const list = data?.list || []
    comments.value = commentPage.value === 1 ? list : comments.value.concat(list)
    commentTotal.value = Number(data?.total || 0)
  } finally {
    commentLoading.value = false
  }
}

const openComments = () => {
  commentsOpen.value = true
  loadComments(true)
}

const moreComments = () => {
  commentPage.value += 1
  loadComments(false)
}

const submitComment = async () => {
  if (!userStore.token) {
    ElMessage.warning('登录后即可发表评论')
    return
  }
  const text = commentText.value.trim()
  if (!text) return
  const vo = await addComment({
    novelId: novel.value.id,
    chapterId: chapter.value.id,
    content: text
  })
  comments.value.unshift(vo)
  commentText.value = ''
  commentTotal.value += 1
}

const removeComment = async (row) => {
  await deleteComment(row.id)
  comments.value = comments.value.filter((c) => c.id !== row.id)
  commentTotal.value = Math.max(0, commentTotal.value - 1)
}

const likeOne = async (row) => {
  if (!userStore.token) {
    ElMessage.warning('登录后即可点赞')
    return
  }
  await likeComment(row.id)
  row.liked = true
  row.likeCount = Number(row.likeCount || 0) + 1
}

// ================= 阅读位置保存 / 恢复 =================
const scrollEl = ref(null) // 沉浸滚动容器
const pageStage = ref(null)

function scroller() {
  return readerStore.immersive ? scrollEl.value : document.scrollingElement || document.documentElement
}

function currentScrollTop() {
  const el = scroller()
  return el ? el.scrollTop : 0
}

function savePos() {
  if (!chapter.value) return
  const pos = settings.mode === 'page'
    ? { mode: 'page', page: page.value }
    : { mode: 'scroll', scrollTop: currentScrollTop() }
  // 以实际章节对象 id 为准，避免路由参数在跳转/卸载瞬间已变空（chapterId.value 为 route.params.cid）
  const cid = chapter.value.id
  setChapterPos(novelId.value, cid, pos)
  const bookmark = {
    novelId: novelId.value,
    novelTitle: novel.value.title || '',
    chapterId: cid,
    chapterNo: chapter.value.chapterNo,
    chapterTitle: chapter.value.title || ''
  }
  setBookmark(bookmark)
  pushProgress(bookmark, pos) // 云同步「读到哪一章」+ 章内位置（内部防抖 + 静默失败）
}

function restorePosition() {
  if (settings.mode === 'page') return // 页码由 computePages() 恢复
  const saved = getChapterPos(novelId.value, chapterId.value)
  const top = saved && saved.mode === 'scroll' ? (saved.scrollTop || 0) : 0
  nextTick(() => {
    const el = scroller()
    if (el) el.scrollTop = top
  })
}

let saveTimer = null
function onScroll() {
  // 进度条实时更新
  const el = scroller()
  if (el) {
    const max = el.scrollHeight - el.clientHeight
    progress.value = max > 0 ? Math.round((el.scrollTop / max) * 100) : 0
  }
  // 节流落盘位置
  if (saveTimer) return
  saveTimer = setTimeout(() => {
    saveTimer = null
    savePos()
  }, 300)
}

const progress = ref(0)

// ================= 章节翻页 =================
function goChapter(c) {
  if (!c) return
  drawer.value = false
  savePos()
  router.push(`/novel/${novelId.value}/chapter/${c.id}`)
}

function flipForward() {
  if (settings.mode === 'page') {
    if (page.value < pageCount.value - 1) goPage(page.value + 1)
    else goChapter(nextChapter.value)
  } else {
    goChapter(nextChapter.value)
  }
}

function flipBackward() {
  if (settings.mode === 'page') {
    if (page.value > 0) goPage(page.value - 1)
    else goChapter(prevChapter.value)
  } else {
    goChapter(prevChapter.value)
  }
}

// ================= 仿真翻页（手动分页 + 横向滑动动画） =================
const pages = ref([])
const page = ref(0)
const pageCount = computed(() => pages.value.length)
const stageW = ref(0)
const stageH = ref(0)
const PAD_X = 56
const PAD_Y = 48
const GAP = 80

let measureEl = null
function getMeasureEl() {
  if (!measureEl) {
    measureEl = document.createElement('div')
    measureEl.style.cssText =
      'position:absolute;left:-99999px;top:0;visibility:hidden;pointer-events:none;' +
      'margin:0;padding:0;white-space:pre-wrap;word-break:break-word;overflow:hidden;text-align:justify;'
    document.body.appendChild(measureEl)
  }
  return measureEl
}

function applyMeasureStyle(cw, ch) {
  const el = getMeasureEl()
  el.style.width = cw + 'px'
  el.style.height = ch + 'px'
  el.style.fontSize = settings.fontSize + 'px'
  el.style.fontFamily = fontFamily.value
  el.style.lineHeight = settings.lineHeight
}

function fits(text, maxH) {
  const el = getMeasureEl()
  el.textContent = text
  return el.scrollHeight <= maxH + 1
}

function splitOne(text, maxH) {
  if (fits(text, maxH)) return { page: text, rest: '' }
  let lo = 1, hi = text.length, best = 0
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (fits(text.slice(0, mid), maxH)) { best = mid; lo = mid + 1 }
    else hi = mid - 1
  }
  if (best <= 0) best = 1
  return { page: text.slice(0, best), rest: text.slice(best) }
}

function computePages() {
  const stage = pageStage.value
  if (!stage) return
  const w = stage.clientWidth
  const h = stage.clientHeight
  if (w < 100 || h < 100) return
  stageW.value = w
  stageH.value = h
  const cw = w - PAD_X * 2
  const ch = h - PAD_Y * 2
  if (cw <= 0 || ch <= 0) return
  applyMeasureStyle(cw, ch)

  const text = content.value || ''
  const out = []
  let rest = text
  let guard = 0
  while (rest.length && guard < 20000) {
    const r = splitOne(rest, ch)
    out.push(r.page)
    rest = r.rest
    guard++
  }
  if (!out.length) out.push('')
  pages.value = out

  const saved = getChapterPos(novelId.value, chapterId.value)
  const target = saved && saved.mode === 'page' ? saved.page : 0
  page.value = clamp(target, 0, out.length - 1)
}

function goPage(i) {
  page.value = clamp(i, 0, pageCount.value - 1)
  savePos()
}

const trackStyle = computed(() => ({
  transform: `translateX(${-(page.value * (stageW.value + GAP))}px)`,
  transition: 'transform .3s cubic-bezier(.22,.7,.3,1)'
}))
const pageBoxStyle = computed(() => ({
  width: stageW.value + 'px',
  height: stageH.value + 'px',
  padding: `${PAD_Y}px ${PAD_X}px`
}))

// 触摸翻页
let touchX = 0, touchY = 0
function onTouchStart(e) {
  touchX = e.touches[0].clientX
  touchY = e.touches[0].clientY
}
function onTouchEnd(e) {
  const dx = e.changedTouches[0].clientX - touchX
  const dy = e.changedTouches[0].clientY - touchY
  if (Math.abs(dx) < 40 || Math.abs(dx) < Math.abs(dy)) return
  if (dx < 0) flipForward()
  else flipBackward()
}

// ================= 自动滚动 =================
const autoPlaying = ref(false)
let rafId = null
let lastTs = 0
function startAuto() {
  if (settings.mode !== 'scroll' || autoPlaying.value) return
  autoPlaying.value = true
  lastTs = 0
  rafId = requestAnimationFrame(tick)
}
function pauseAuto() {
  autoPlaying.value = false
  if (rafId) cancelAnimationFrame(rafId)
  rafId = null
}
function toggleAuto() {
  autoPlaying.value ? pauseAuto() : startAuto()
}
function tick(ts) {
  if (!autoPlaying.value) return
  const el = scroller()
  if (el && lastTs) {
    const dt = (ts - lastTs) / 1000
    el.scrollTop += settings.autoSpeed * dt
    const max = el.scrollHeight - el.clientHeight
    if (el.scrollTop >= max - 1) { pauseAuto(); return }
  }
  lastTs = ts
  rafId = requestAnimationFrame(tick)
}

// ================= 沉浸全屏 =================
function enterImmersive() {
  // 先记录当前窗口滚动位置，进入沉浸后平滑衔接
  const curScroll = (document.scrollingElement || document.documentElement).scrollTop || 0
  readerStore.enterImmersive()
  document.body.style.overflow = 'hidden'
  nextTick(() => {
    if (settings.mode === 'page') computePages()
    else if (scrollEl.value) scrollEl.value.scrollTop = curScroll
  })
}
function exitImmersive() {
  const curScroll = scrollEl.value && settings.mode === 'scroll' ? scrollEl.value.scrollTop : 0
  readerStore.exitImmersive()
  document.body.style.overflow = ''
  if (settings.mode === 'scroll') {
    nextTick(() => {
      const el = document.scrollingElement || document.documentElement
      if (el) el.scrollTop = curScroll
    })
  }
}
function toggleImmersive() {
  readerStore.immersive ? exitImmersive() : enterImmersive()
}

function setMode(mode) {
  settings.mode = mode
  if (mode === 'page') {
    settingsOpen.value = false
    enterImmersive()
    nextTick(computePages)
  }
}

// ================= 设置面板 =================
const settingsOpen = ref(false)

// ================= 解锁 =================
const doUnlock = async (wholeBook = false) => {
  try {
    await unlockNovel(wholeBook ? { novelId: novelId.value } : { novelId: novelId.value, chapterId: chapterId.value })
    ElMessage.success(wholeBook ? '已解锁整本' : '解锁成功')
    await userStore.fetchUserInfo()
    await load()
  } catch (e) { /* 余额不足等已在拦截器提示 */ }
}

// ================= 键盘 / 事件 =================
function onKeydown(e) {
  if (e.key === 'Escape') {
    if (readerStore.immersive) exitImmersive()
    return
  }
  if (!readerStore.immersive) return
  if (e.key === 'ArrowLeft') { e.preventDefault(); flipBackward() }
  else if (e.key === 'ArrowRight') { e.preventDefault(); flipForward() }
}

let resizeTimer = null
function onResize() {
  if (settings.mode !== 'page') return
  clearTimeout(resizeTimer)
  resizeTimer = setTimeout(computePages, 150)
}

watch(chapterId, () => load())
watch([() => settings.fontSize, () => settings.fontFamily, () => settings.lineHeight], () => {
  if (settings.mode === 'page') nextTick(computePages)
})
watch(() => settings.mode, (m) => {
  if (m === 'page') {
    nextTick(computePages)
  } else {
    // 切回滚动：恢复到该章上次滚动位置
    nextTick(() => {
      const saved = getChapterPos(novelId.value, chapterId.value)
      const el = scrollEl.value
      if (el) el.scrollTop = saved && saved.mode === 'scroll' ? (saved.scrollTop || 0) : 0
    })
  }
})

onMounted(async () => {
  window.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('resize', onResize)
  await syncPreference() // 先拉云端偏好，避免 mode=page 时漏算分页
  load()
})
onBeforeUnmount(() => {
  savePos()
  pauseAuto()
  window.removeEventListener('scroll', onScroll)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('resize', onResize)
  document.body.style.overflow = ''
  readerStore.exitImmersive()
  if (measureEl && measureEl.parentNode) measureEl.parentNode.removeChild(measureEl)
})
</script>

<template>
  <div class="reader-root" :style="rootStyle">
    <div v-if="!readerStore.immersive" class="reader">
      <div class="toolbar">
        <div class="tb-left">
          <el-button text @click="router.push(`/novel/${novelId}`)">‹ 返回详情</el-button>
          <span class="tb-title">{{ novel.title }}</span>
        </div>
        <div class="tb-right">
          <div class="font-ctl">
            <el-button size="small" text @click="settings.fontSize = clamp(settings.fontSize - 2, 14, 28)">A-</el-button>
            <span class="font-val">{{ settings.fontSize }}</span>
            <el-button size="small" text @click="settings.fontSize = clamp(settings.fontSize + 2, 14, 28)">A+</el-button>
          </div>
          <el-button size="small" @click="settingsOpen = true">Aa 设置</el-button>
          <el-button size="small" @click="openComments"
            ><el-icon><ChatDotRound /></el-icon><span>本章说</span></el-button
          >
          <el-button size="small" @click="drawer = true"><el-icon><List /></el-icon><span>目录</span></el-button>
          <el-button size="small" type="primary" plain @click="enterImmersive"><el-icon><FullScreen /></el-icon><span>沉浸</span></el-button>
        </div>
      </div>
      <div class="progress"><div class="progress-bar" :style="{ width: progress + '%' }"></div></div>

      <div class="body" v-loading="loading">
        <el-card v-if="failed" shadow="never" class="lock-card">
          <div class="lock-icon"><el-icon><CircleClose /></el-icon></div>
          <h2>这一章暂时打不开</h2>
          <p class="lock-tip">作品可能已被下架或删除，也可能只是暂时无法访问。</p>
          <div class="lock-actions">
            <el-button type="primary" size="large" @click="router.push('/')">回到首页</el-button>
            <el-button size="large" @click="router.back()">返回上一页</el-button>
          </div>
        </el-card>

        <el-card v-else-if="locked && chapter" shadow="never" class="lock-card">
          <div class="lock-icon"><el-icon><Lock /></el-icon></div>
          <h2>{{ chapter.title }}</h2>
          <p class="lock-tip">本章为付费章节，需 <b>{{ chapter.unlockCoin }}</b> 币解锁后阅读</p>
          <p class="lock-balance">当前余额：<b>{{ userStore.coinBalance }}</b> 币</p>
          <div class="lock-actions">
            <el-button type="primary" size="large" @click="doUnlock(false)">解锁本章（{{ chapter.unlockCoin }} 币）</el-button>
            <el-button v-if="novel.coinPrice > 0" type="warning" size="large" plain @click="doUnlock(true)">解锁整本（{{ novel.coinPrice }} 币）</el-button>
          </div>
          <p v-if="userStore.coinBalance < chapter.unlockCoin" class="lock-warn">余额不足，请先前往「我的」充值</p>
        </el-card>

        <el-card v-else-if="chapter" shadow="never" class="content-card">
          <h1 class="ch-title">{{ chapter.title }}</h1>
          <div class="ch-meta">第 {{ chapter.chapterNo }} 章 · {{ chapter.wordCount }} 字</div>
          <div class="ch-content" :style="scrollContentStyle">{{ content }}</div>
          <div class="pager">
            <el-button :disabled="!prevChapter" @click="goChapter(prevChapter)">‹ 上一章</el-button>
            <span class="pager-pos">{{ chapter?.chapterNo || 0 }} / {{ totalChapters }}</span>
            <el-button :disabled="!nextChapter" @click="goChapter(nextChapter)">下一章 ›</el-button>
          </div>
        </el-card>
      </div>
    </div>

    <div v-else class="immersive">
      <div class="imm-topbar">
        <div class="imm-left">
          <button class="imm-btn" @click="exitImmersive"><el-icon><Close /></el-icon></button>
          <span class="imm-title">{{ novel.title }} · {{ chapter && chapter.title }}</span>
        </div>
        <div class="imm-right">
          <button v-if="settings.mode === 'scroll'" class="imm-btn" @click="toggleAuto">
            <el-icon v-if="autoPlaying"><VideoPause /></el-icon>
            <el-icon v-else><VideoPlay /></el-icon>
          </button>
          <button class="imm-btn" @click="openComments"><el-icon><ChatDotRound /></el-icon></button>
          <button class="imm-btn" @click="settingsOpen = true">Aa</button>
          <button class="imm-btn" @click="drawer = true"><el-icon><List /></el-icon></button>
        </div>
      </div>

      <div
        v-if="settings.mode === 'scroll'"
        ref="scrollEl"
        class="imm-scroll"
        @scroll="onScroll"
      >
        <div class="imm-pad">
          <h1 class="ch-title">{{ chapter && chapter.title }}</h1>
          <div class="ch-meta">第 {{ chapter && chapter.chapterNo }} 章 · {{ chapter && chapter.wordCount }} 字</div>
          <div class="ch-content" :style="scrollContentStyle">{{ content }}</div>
        </div>
      </div>

      <div
        v-else
        ref="pageStage"
        class="page-stage"
        @touchstart="onTouchStart"
        @touchend="onTouchEnd"
      >
        <div class="page-track" :style="trackStyle">
          <div v-for="(p, i) in pages" :key="i" class="page" :style="pageBoxStyle">
            <div class="page-text" :style="fontStyle">{{ p }}</div>
          </div>
        </div>
        <div class="tap-zone tap-left" @click="flipBackward"></div>
        <div class="tap-zone tap-right" @click="flipForward"></div>
      </div>

      <div class="imm-footer">
        <span v-if="settings.mode === 'page'">{{ page + 1 }} / {{ pageCount }} 页</span>
        <span v-else>{{ progress }}%</span>
        <span class="imm-pos">{{ chapter?.chapterNo || 0 }} / {{ totalChapters }} 章</span>
      </div>
    </div>

    <div class="dim-mask" :style="dimMaskStyle"></div>

    <transition name="sheet">
      <div v-if="settingsOpen" class="sheet-mask" @click.self="settingsOpen = false">
        <div class="sheet">
          <div class="sheet-head">
            <span>阅读设置</span>
            <button class="imm-btn" @click="settingsOpen = false"><el-icon><Close /></el-icon></button>
          </div>

          <div class="sheet-row">
            <span class="row-label">主题</span>
            <div class="theme-swatches">
              <div
                v-for="(t, k) in THEMES"
                :key="k"
                class="swatch"
                :class="{ active: settings.theme === k }"
                :style="{ background: t.bg, color: t.fg }"
                @click="settings.theme = k"
              >{{ t.label }}</div>
            </div>
          </div>

          <div class="sheet-row">
            <span class="row-label">字号</span>
            <el-slider v-model="settings.fontSize" :min="14" :max="28" :step="1" class="row-slider" />
          </div>

          <div class="sheet-row">
            <span class="row-label">字族</span>
            <div class="seg">
              <span :class="{ active: settings.fontFamily === 'song' }" @click="settings.fontFamily = 'song'">宋</span>
              <span :class="{ active: settings.fontFamily === 'hei' }" @click="settings.fontFamily = 'hei'">黑</span>
              <span :class="{ active: settings.fontFamily === 'kai' }" @click="settings.fontFamily = 'kai'">楷</span>
            </div>
          </div>

          <div class="sheet-row">
            <span class="row-label">行距</span>
            <el-slider v-model="settings.lineHeight" :min="1.4" :max="3.2" :step="0.1" class="row-slider" />
          </div>

          <div class="sheet-row">
            <span class="row-label">栏宽</span>
            <el-slider v-model="settings.columnWidth" :min="480" :max="1000" :step="20" class="row-slider" />
          </div>

          <div class="sheet-row">
            <span class="row-label">亮度</span>
            <el-slider v-model="settings.brightness" :min="0.3" :max="1" :step="0.05" class="row-slider" />
          </div>

          <div class="sheet-row">
            <span class="row-label">翻页</span>
            <div class="seg">
              <span :class="{ active: settings.mode === 'scroll' }" @click="setMode('scroll')">滚动</span>
              <span :class="{ active: settings.mode === 'page' }" @click="setMode('page')">仿真</span>
            </div>
          </div>

          <div class="sheet-row" v-if="settings.mode === 'scroll'">
            <span class="row-label">自动阅读</span>
            <div class="auto-ctl">
              <el-button size="small" :type="autoPlaying ? 'warning' : 'primary'" @click="toggleAuto">
                {{ autoPlaying ? '暂停' : '开始' }}
              </el-button>
              <el-slider v-model="settings.autoSpeed" :min="10" :max="240" :step="10" class="row-slider" />
            </div>
          </div>

          <div class="sheet-row sheet-actions">
            <el-button size="small" @click="drawer = true; settingsOpen = false"><el-icon><List /></el-icon><span>目录</span></el-button>
            <el-button size="small" type="primary" plain @click="toggleImmersive">
              {{ readerStore.immersive ? '退出沉浸' : '沉浸全屏' }}
            </el-button>
          </div>
        </div>
      </div>
    </transition>

    <el-drawer v-model="drawer" title="目录" direction="rtl" size="360px" @open="openToc">
      <div class="toc">
        <div
          v-for="c in tocList"
          :key="c.id"
          class="toc-item"
          :class="{ active: c.id === chapterId }"
          @click="goChapter(c)"
        >
          <span class="toc-no">第{{ c.chapterNo }}章</span>
          <span class="toc-title">{{ c.title }}</span>
          <span v-if="c.unlockCoin > 0" class="toc-lock"><el-icon><Lock /></el-icon></span>
        </div>
        <div v-if="tocTotal > tocPageSize" class="toc-pager">
          <span class="pager-info">第 {{ tocPageNum }} / {{ Math.ceil(tocTotal / tocPageSize) }} 页 · 每页 {{ tocPageSize }} 章</span>
          <el-pagination
            small
            v-model:current-page="tocPageNum"
            :page-size="tocPageSize"
            :total="tocTotal"
            layout="prev, pager, next"
            prev-text="上一页"
            next-text="下一页"
            @current-change="loadToc"
          />
        </div>
      </div>
    </el-drawer>

    <el-drawer v-model="commentsOpen" direction="rtl" size="400px" :with-header="false">
      <div class="cm-head">
        <span class="cm-title">本章说</span>
        <span class="cm-sub">第 {{ chapter?.chapterNo }} 章 · {{ commentTotal }} 条</span>
      </div>

      <div class="cm-compose">
        <el-input
          v-model="commentText"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          :placeholder="userStore.token ? '说说这一章…' : '登录后即可发表评论'"
        />
        <div class="cm-compose-ops">
          <el-button type="primary" size="small" @click="submitComment">发表</el-button>
        </div>
      </div>

      <div v-loading="commentLoading" class="cm-list">
        <div v-if="!comments.length && !commentLoading" class="cm-empty">
          还没有人在这里留言，来说两句
        </div>
        <div v-for="c in comments" :key="c.id" class="cm-item">
          <div class="cm-row">
            <span class="cm-name">{{ c.authorName || '读者' }}</span>
            <span class="cm-time">{{ fmtTime(c.createTime, 16) }}</span>
          </div>
          <div class="cm-text">{{ c.content }}</div>
          <div class="cm-ops">
            <span class="cm-op" :class="{ on: c.liked }" @click="likeOne(c)">
              <el-icon><Pointer /></el-icon> {{ c.likeCount || 0 }}
            </span>
            <span v-if="String(c.userId) === String(userStore.userId)" class="cm-op" @click="removeComment(c)">
              删除
            </span>
          </div>
        </div>
        <div v-if="comments.length < commentTotal" class="cm-more" @click="moreComments">加载更多</div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.reader-root { min-height: 100vh; }

/* 本章说：抽屉里的评论流，配色跟随阅读器主题变量 */
.cm-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 14px; }
.cm-title { font-family: var(--serif); font-size: 18px; font-weight: 600; }
.cm-sub { color: var(--muted); font-size: 12.5px; }
.cm-compose { margin-bottom: 16px; }
.cm-compose-ops { display: flex; justify-content: flex-end; margin-top: 8px; }
.cm-list { min-height: 120px; }
.cm-empty { color: var(--muted); font-size: 13px; text-align: center; padding: 36px 0; }
.cm-item { padding: 11px 0; border-bottom: 1px solid var(--line-soft); }
.cm-row { display: flex; align-items: baseline; gap: 8px; margin-bottom: 4px; }
.cm-name { font-size: 13px; font-weight: 500; }
.cm-time { color: var(--muted); font-size: 12px; }
.cm-text { font-size: 13.5px; line-height: 1.7; word-break: break-word; }
.cm-ops { display: flex; gap: 16px; margin-top: 6px; }
.cm-op { display: inline-flex; align-items: center; gap: 4px; color: var(--muted); font-size: 12.5px; cursor: pointer; }
.cm-op:hover { color: var(--cinnabar); }
.cm-op.on { color: var(--cinnabar); }
.cm-more { text-align: center; color: var(--muted); font-size: 12.5px; padding: 14px 0; cursor: pointer; }
.cm-more:hover { color: var(--cinnabar); }

/* ================= 非沉浸 ================= */
.reader { max-width: 820px; margin: 0 auto; }
.toolbar {
  position: sticky; top: 62px; z-index: 20;
  display: flex; align-items: center; justify-content: space-between;
  background: var(--reader-bar);
  padding: 10px 16px; border-radius: 12px; border: 1px solid rgba(128, 128, 150, 0.15);
}
.tb-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.tb-title { font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; color: var(--reader-fg); }
.tb-right { display: flex; align-items: center; gap: 12px; }
.font-ctl { display: flex; align-items: center; gap: 4px; }
.font-val { font-size: 12px; color: var(--reader-muted); min-width: 20px; text-align: center; }
.progress { height: 3px; background: rgba(128, 128, 150, 0.18); border-radius: 2px; margin: 8px 0 18px; overflow: hidden; }
.progress-bar { height: 100%; background: var(--cinnabar); transition: width .1s; }

.lock-card { text-align: center; padding: 40px 20px; }
.lock-icon { font-size: 44px; color: var(--reader-muted); }
.lock-card h2 { font-family: var(--serif); margin: 14px 0 8px; font-size: 22px; font-weight: 600; color: var(--reader-fg); }
.lock-tip { color: var(--reader-muted); margin-bottom: 6px; }
.lock-balance { color: var(--reader-muted); font-size: 13px; margin-bottom: 20px; }
.lock-actions { display: flex; gap: 12px; justify-content: center; flex-wrap: wrap; }
.lock-warn { margin-top: 16px; color: #e6a23c; font-size: 13px; }

.content-card :deep(.el-card__body) { padding: 32px 36px 40px; }
.content-card { background: var(--reader-bg); }
.ch-title { font-family: var(--serif); font-size: 26px; font-weight: 600; line-height: 1.4; color: var(--reader-fg); }
.ch-meta { color: var(--reader-muted); font-size: 13px; margin: 10px 0 26px; }
.ch-content {
  color: var(--reader-fg); white-space: pre-wrap; word-break: break-word;
  text-align: justify;
}
.pager {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 40px; padding-top: 20px; border-top: 1px solid rgba(128, 128, 150, 0.2);
}
.pager-pos { color: var(--reader-muted); font-size: 13px; }

/* ================= 沉浸全屏 ================= */
.immersive {
  position: fixed; inset: 0; z-index: 300;
  background: var(--reader-bg); color: var(--reader-fg);
  display: flex; flex-direction: column; overflow: hidden;
}
.imm-topbar {
  display: flex; align-items: center; justify-content: space-between;
  height: 54px; padding: 0 16px; flex-shrink: 0;
  background: var(--reader-bar); border-bottom: 1px solid rgba(128, 128, 150, 0.14);
}
.imm-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.imm-title { font-size: 14px; color: var(--reader-fg); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.imm-right { display: flex; align-items: center; gap: 6px; }
.imm-btn {
  border: none; background: transparent; color: var(--reader-fg);
  font-size: 18px; width: 36px; height: 36px; border-radius: 8px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
}
.imm-btn:hover { background: rgba(128, 128, 150, 0.16); }
.imm-scroll { flex: 1; overflow-y: auto; }
.imm-pad { padding: 28px 0 60px; }
.imm-footer {
  display: flex; align-items: center; justify-content: center; gap: 24px;
  height: 40px; flex-shrink: 0; font-size: 12px; color: var(--reader-muted);
}
.imm-pos { }

/* 仿真翻页 */
.page-stage { flex: 1; position: relative; overflow: hidden; }
.page-track { display: flex; height: 100%; will-change: transform; }
.page { flex-shrink: 0; box-sizing: border-box; }
.page-text { color: var(--reader-fg); white-space: pre-wrap; word-break: break-word; text-align: justify; }
.tap-zone { position: absolute; top: 0; bottom: 0; width: 30%; z-index: 5; }
.tap-left { left: 0; }
.tap-right { right: 0; }

/* 亮度遮罩 */
.dim-mask {
  position: fixed; left: 0; right: 0; bottom: 0; pointer-events: none;
  background: #000;
}

/* ================= 设置面板 ================= */
.sheet-mask {
  position: fixed; inset: 0; z-index: 500; background: rgba(0, 0, 0, 0.4);
  display: flex; align-items: flex-end; justify-content: center;
}
.sheet {
  width: 100%; max-width: 720px; border-radius: 18px 18px 0 0;
  background: var(--reader-bar); color: var(--reader-fg);
  padding: 18px 24px 24px; max-height: 82vh; overflow-y: auto;
}
.sheet-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; font-weight: 700; }
.sheet-row { display: flex; align-items: center; gap: 16px; padding: 10px 0; border-bottom: 1px solid rgba(128, 128, 150, 0.12); }
.row-label { width: 64px; font-size: 13px; color: var(--reader-muted); flex-shrink: 0; }
.row-slider { flex: 1; }
.sheet-actions { border-bottom: none; justify-content: flex-end; gap: 10px; }

.theme-swatches { display: flex; gap: 10px; }
.swatch {
  padding: 6px 14px; border-radius: 20px; font-size: 13px; cursor: pointer;
  border: 1px solid rgba(128, 128, 150, 0.3);
}
.swatch.active { outline: 2px solid #b23a2e; outline-offset: 1px; }

.seg { display: flex; background: rgba(128, 128, 150, 0.12); border-radius: 8px; padding: 3px; }
.seg span {
  padding: 5px 18px; font-size: 13px; cursor: pointer; border-radius: 6px; color: var(--reader-muted);
}
.seg span.active { background: var(--reader-bg); color: var(--reader-fg); font-weight: 600; box-shadow: 0 1px 4px rgba(0,0,0,0.15); }

.auto-ctl { display: flex; align-items: center; gap: 12px; flex: 1; }
.auto-ctl .row-slider { flex: 1; }

.sheet-enter-active, .sheet-leave-active { transition: opacity .2s; }
.sheet-enter-active .sheet, .sheet-leave-active .sheet { transition: transform .25s; }
.sheet-enter-from, .sheet-leave-to { opacity: 0; }
.sheet-enter-from .sheet, .sheet-leave-to .sheet { transform: translateY(100%); }

/* ================= 目录 ================= */
.toc { display: flex; flex-direction: column; gap: 2px; }
.toc-item {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  border-radius: 8px; cursor: pointer; transition: background .15s;
}
.toc-item:hover { background: rgba(128, 128, 150, 0.1); }
.toc-item.active { background: rgba(178,58,46,0.12); color: #b23a2e; font-weight: 600; }
.toc-no { font-size: 12px; color: var(--reader-muted); flex-shrink: 0; }
.toc-title { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 14px; }
.toc-lock { font-size: 12px; }
.toc-pager { display: flex; flex-direction: column; align-items: center; gap: 6px; margin-top: 12px; }
.toc-pager .pager-info { font-size: 12px; color: #909399; }

/* 窄屏（手机）：工具条一行为「返回详情 + 书名 + 字号 + 4 个按钮」，
   在 390px 宽度下无法容纳，右侧按钮被截断。改为允许换行：
   左区（返回 + 书名）独占一行，右区按钮落到第二行。 */
@media (max-width: 768px) {
  .toolbar { flex-wrap: wrap; gap: 8px; padding: 8px 12px; }
  .tb-left { flex: 1 1 100%; }
  .tb-right { width: 100%; flex-wrap: wrap; gap: 8px; }
}
</style>
