<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  getNovelDetail, getChapterPage, readNovel, likeNovel, unlockNovel, getUnlockStatus,
  getCommentPage, getCommentReplies, addComment, deleteComment, likeComment,
  getBookshelfStatus, addBookshelf, removeBookshelf
} from '../api'
import { posterVars, posterChar, fmtWan } from '../utils/cover'
import { getBookmark } from '../utils/reading'
import { pullProgress } from '../utils/readerSync'
import { fmtTime } from '../utils/format'
import { useUserStore } from '../store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const novel = ref({})
const chapters = ref([])          // 当前页章节（目录服务端分页，不再是全量）
const unlockedMap = ref({})
const wholeBook = ref(false)
// 目录分页状态
const chapterPageNum = ref(1)
const chapterPageSize = 50
const chapterTotal = ref(0)
// 所有已单独解锁的章 ID（字符串），跨页复用回填解锁状态
const unlockedSet = ref(new Set())
// 挂载后先拉云端书签再回填，决定「继续阅读」还是「开始阅读」
const bookmark = ref(null)
const inShelf = ref(false)
// 作品不存在 / 未过审 / 已删除（后端按 404 语义返回）：页面转为兜底态
const notFound = ref(false)

// 保持字符串：雪花 ID 经 Number() 会丢精度，导致跳阅读器/解锁时 ID 错乱
const novelId = computed(() => route.params.id)
// 本书是否存在续读记录（雪花 ID 按字符串比较）
const hasBookmark = computed(
  () => bookmark.value && String(bookmark.value.novelId) === String(novelId.value)
)
const totalWords = computed(() => Number(novel.value.wordCount || 0))

// 回填当前页解锁状态：免费章/整本解锁/已单独解锁均视为可读
const applyUnlockToPage = () => {
  for (const c of chapters.value) {
    unlockedMap.value[c.id] =
      c.unlockCoin === 0 || wholeBook.value || unlockedSet.value.has(String(c.id))
  }
}

// 目录服务端分页加载
const loadChapterPage = async (pageNum = 1) => {
  const data = await getChapterPage(novelId.value, { pageNum, pageSize: chapterPageSize })
  chapters.value = data.list || []
  chapterTotal.value = Number(data.total) || 0
  chapterPageNum.value = pageNum
  applyUnlockToPage()
}

const load = async () => {
  const id = route.params.id
  try {
    // 云同步：先拉云端书签覆盖本地镜像，再回填（跨设备续读）。
    // pullProgress 内部自己兜异常（游客拉不到云端进度是预期内的）
    await pullProgress()
    bookmark.value = getBookmark()
    // silent：本页对「作品不存在」有专门的兜底界面，不必再弹一次全局错误提示
    novel.value = await getNovelDetail(id, { silent: true })
    // 解锁状态：单独 try。游客态下该请求必然返回 401，而「游客尚未解锁任何章」
    // 属正常结果。若不单独捕获，异常会落到最外层 catch，
    // 将一本正常的书显示为「作品不存在或已下架」（silent 仅抑制提示，不代表不会 reject）
    try {
      const status = await getUnlockStatus(id)
      wholeBook.value = !!status?.wholeBook
      unlockedSet.value = new Set(status?.chapterIds || [])
    } catch (e) {
      wholeBook.value = false
      unlockedSet.value = new Set()
    }
    await loadChapterPage(1)
  } catch (e) {
    notFound.value = true
  }
}

const onLike = async () => {
  const res = await likeNovel(route.params.id)
  novel.value.liked = res.liked
  novel.value.likeCount = res.likeCount
  ElMessage.success(res.liked ? '点赞成功' : '已取消点赞')
}

// 打开阅读器：首次进入计一次阅读量
const openReader = async (chapter) => {
  if (!chapter) {
    ElMessage.warning('本书暂无章节')
    return
  }
  try {
    await readNovel(route.params.id)
    novel.value.readCount = Number(novel.value.readCount || 0) + 1
  } catch (e) { /* 限流等已在拦截器提示 */ }
  router.push(`/novel/${novelId.value}/chapter/${chapter.id}`)
}

// 开始 / 继续阅读：有本书续读记录则跳回上次章节，否则从第一章开始
// 目录分页后不再持有全量章节，改用书签章 ID / novel.firstChapterId 直接跳转
const startReading = () => {
  const ch = hasBookmark.value
    ? { id: String(bookmark.value.chapterId) }
    : (novel.value.firstChapterId ? { id: novel.value.firstChapterId } : null)
  openReader(ch)
}

// 作者主页：管理员录入的公版书没有发布者，userId 为空时不渲染链接
const goAuthor = () => {
  if (novel.value.userId) router.push(`/author/${novel.value.userId}`)
}

const unlock = async (chapter) => {
  try {
    await unlockNovel({ novelId: novelId.value, chapterId: chapter.id })
    ElMessage.success('解锁成功，消耗 ' + chapter.unlockCoin + ' 币')
    unlockedMap.value[chapter.id] = true
    await userStore.fetchUserInfo()
  } catch (e) { /* 余额不足等已在拦截器提示 */ }
}

const unlockAll = async () => {
  try {
    await unlockNovel({ novelId: novelId.value })
    ElMessage.success('解锁整本成功')
    wholeBook.value = true
    applyUnlockToPage()
    await userStore.fetchUserInfo()
  } catch (e) { /* 已在拦截器提示 */ }
}

// ================= 评论 =================
const comments = ref([])
const commentTotal = ref(0)
const commentPage = ref(1)
const commentLoading = ref(false)
const commentText = ref('')
const replyTarget = ref(null)      // { id, authorName } 或 null
const replyText = ref('')
const repliesMap = ref({})         // { commentId: [CommentVO] }

const isOwn = (c) => String(c.userId) === String(userStore.userId)

const loadComments = async () => {
  commentLoading.value = true
  try {
    const data = await getCommentPage(novelId.value, { pageNum: commentPage.value, pageSize: 10 })
    comments.value = commentPage.value === 1 ? data.list : comments.value.concat(data.list)
    // total 是后端 PageResult 的 Long，序列化成字符串；不转数字的话下面
    // commentTotal.value += 1 会变成字符串拼接（「12」 → 「121」），显示成「121 条」
    commentTotal.value = Number(data.total || 0)
  } catch (e) { /* 已在拦截器提示 */ }
  finally { commentLoading.value = false }
}

const loadMoreComments = () => {
  commentPage.value += 1
  loadComments()
}

const submitComment = async () => {
  const content = commentText.value.trim()
  if (!content) { ElMessage.warning('评论内容不能为空'); return }
  try {
    const vo = await addComment({ novelId: novelId.value, content })
    comments.value.unshift(vo)
    commentText.value = ''
    commentTotal.value += 1
    ElMessage.success('评论成功')
  } catch (e) { /* 已在拦截器提示 */ }
}

const toggleReply = (comment) => {
  const opening = !(replyTarget.value && replyTarget.value.id === comment.id)
  replyTarget.value = opening ? comment : null
  replyText.value = ''
  if (opening) loadReplies(comment)
}

const loadReplies = async (comment) => {
  if (repliesMap.value[comment.id]) return
  try {
    repliesMap.value[comment.id] = await getCommentReplies(comment.id)
  } catch (e) { /* 忽略 */ }
}

const submitReply = async () => {
  if (!replyTarget.value) return
  const content = replyText.value.trim()
  if (!content) { ElMessage.warning('回复不能为空'); return }
  try {
    const vo = await addComment({ novelId: novelId.value, parentId: replyTarget.value.id, content })
    if (!repliesMap.value[replyTarget.value.id]) repliesMap.value[replyTarget.value.id] = []
    repliesMap.value[replyTarget.value.id].push(vo)
    const target = comments.value.find((c) => c.id === replyTarget.value.id)
    if (target) target.replyCount = (Number(target.replyCount) || 0) + 1
    replyText.value = ''
    replyTarget.value = null
  } catch (e) { /* 已在拦截器提示 */ }
}

const removeComment = async (comment) => {
  try {
    await deleteComment(comment.id)
    comments.value = comments.value.filter((c) => c.id !== comment.id)
    commentTotal.value = Math.max(0, commentTotal.value - 1)
    ElMessage.success('已删除')
  } catch (e) { /* 已在拦截器提示 */ }
}

const doLikeComment = async (comment) => {
  try {
    await likeComment(comment.id)
    comment.likeCount = Number(comment.likeCount || 0) + 1
  } catch (e) { /* 已在拦截器提示 */ }
}

const loadShelfStatus = async () => {
  try {
    inShelf.value = await getBookshelfStatus(novelId.value)
  } catch (e) { /* 忽略 */ }
}

const toggleShelf = async () => {
  try {
    if (inShelf.value) {
      await removeBookshelf(novelId.value)
      inShelf.value = false
      ElMessage.success('已移出书架')
    } else {
      await addBookshelf(novelId.value)
      inShelf.value = true
      ElMessage.success('已加入书架')
    }
  } catch (e) { /* 已在拦截器提示 */ }
}

onMounted(() => {
  load()
  loadComments()
  loadShelfStatus()
})
</script>

<template>
  <div class="detail" v-loading="!novel.id && !notFound">
    <div v-if="notFound" class="nf">
      <el-icon class="nf-ic"><Document /></el-icon>
      <h2>作品不存在或已下架</h2>
      <p>它可能已被作者删除，或尚未通过审核。</p>
      <el-button type="primary" @click="router.push('/novel')">去书库看看</el-button>
    </div>

    <template v-else>
    <el-card shadow="never" class="head-card">
      <div class="head">
        <div class="cover" :style="posterVars(novel)" @click="startReading">
          <img v-if="novel.coverUrl" :src="novel.coverUrl" class="cover-img" alt="封面" />
          <span v-else>{{ posterChar(novel.title) }}</span>
          <div class="cover-open"><el-icon><Reading /></el-icon></div>
        </div>
        <div class="info">
          <div class="title-row">
            <h1>{{ novel.title }}</h1>
            <el-tag v-if="novel.isMine" type="info" effect="plain">我的作品</el-tag>
            <el-tag
              v-if="novel.serialStatusText"
              :type="novel.serialStatus === 1 ? 'info' : ''"
              effect="plain"
            >{{ novel.serialStatusText }}</el-tag>
            <el-tag v-if="novel.offline" type="info" effect="light">已下架</el-tag>
            <el-tag v-if="novel.auditStatus === 1" type="success" effect="light">已过审</el-tag>
            <el-tag v-else-if="novel.auditStatus === 3" type="warning" effect="light">修改审核中</el-tag>
            <el-tag v-else-if="novel.auditStatus === 0" type="warning" effect="light">审核中</el-tag>
            <el-tag v-else type="danger" effect="light">已拒绝</el-tag>
          </div>
          <div class="tags">
            <el-tag type="info" effect="plain">{{ novel.categoryName }}</el-tag>
            <el-tag v-for="t in (novel.tags || '').split(',').filter(Boolean)" :key="t" type="info" effect="plain" class="tag">{{ t }}</el-tag>
          </div>
          <div class="intro">{{ novel.intro }}</div>
          <div class="stats">
            <div class="stat"><span class="v">{{ fmtWan(novel.readCount) }}</span><span class="l">阅读</span></div>
            <div class="stat"><span class="v">{{ fmtWan(novel.likeCount) }}</span><span class="l">点赞</span></div>
            <div class="stat"><span class="v">{{ novel.totalChapters }}</span><span class="l">章节</span></div>
            <div class="stat">
              <span class="v stat-name">
                <a v-if="novel.userId" class="author-link" @click="goAuthor">{{ novel.author }}</a>
                <span v-else>{{ novel.author }}</span>
              </span>
              <span class="l">作者</span>
            </div>
          </div>
          <div v-if="novel.offline" class="offline-note">
            <el-icon><InfoFilled /></el-icon>
            本作品已下架，不再对外展示；已解锁的章节仍可继续阅读。
          </div>
          <div class="actions">
            <el-button type="primary" size="large" @click="startReading">
              {{ hasBookmark ? `继续阅读 · 第${bookmark.chapterNo}章` : '开始阅读' }}
            </el-button>
            <template v-if="novel.isMine">
              <el-button size="large" @click="router.push(`/novel/${novelId}/manage`)">管理作品</el-button>
            </template>
            <template v-else-if="!novel.offline">
              <el-button size="large" :type="novel.liked ? 'danger' : 'default'" @click="onLike">
                {{ novel.liked ? '已点赞' : '点赞' }}
              </el-button>
              <el-button size="large" @click="toggleShelf">{{ inShelf ? '已在书架' : '加入书架' }}</el-button>
              <el-button v-if="novel.coinPrice > 0 && !wholeBook" size="large" @click="unlockAll">解锁整本（{{ novel.coinPrice }} 币）</el-button>
            </template>
          </div>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" style="margin-top: 18px">
      <template #header>
        <div class="toc-header">
          <span>目录</span>
          <span class="hint">共 {{ chapterTotal }} 章 · {{ fmtWan(totalWords) }} 字</span>
        </div>
      </template>
      <el-table :data="chapters" stripe>
        <el-table-column label="章节" width="90">
          <template #default="{ row }">
            <span class="ch-no">第 {{ row.chapterNo }} 章</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" />
        <el-table-column label="字数" width="100">
          <template #default="{ row }">
            <span class="ch-words">{{ row.wordCount }} 字</span>
          </template>
        </el-table-column>
        <el-table-column label="价格" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.unlockCoin === 0" type="success" effect="plain">免费</el-tag>
            <el-tag v-else type="warning" effect="plain">{{ row.unlockCoin }} 币</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center">
          <template #default="{ row }">
            <el-button v-if="unlockedMap[row.id]" type="primary" size="small" @click="openReader(row)">阅读</el-button>
            <el-button v-else type="primary" size="small" plain @click="unlock(row)">解锁本章</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="chapterTotal > chapterPageSize" class="toc-pager">
        <span class="pager-info">第 {{ chapterPageNum }} / {{ Math.ceil(chapterTotal / chapterPageSize) }} 页 · 每页 {{ chapterPageSize }} 章</span>
        <el-pagination
          v-model:current-page="chapterPageNum"
          :page-size="chapterPageSize"
          :total="chapterTotal"
          layout="prev, pager, next"
          background
          prev-text="上一页"
          next-text="下一页"
          @current-change="loadChapterPage"
        />
      </div>
    </el-card>

    <el-card shadow="never" style="margin-top: 18px" class="comment-card">
      <template #header>
        <div class="toc-header">
          <span>评论</span>
          <span class="hint">{{ commentTotal }} 条</span>
        </div>
      </template>

      <div class="comment-input">
        <el-input v-model="commentText" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="写下你的评论…" />
        <div class="comment-input-actions">
          <el-button type="primary" @click="submitComment">发表评论</el-button>
        </div>
      </div>

      <div v-loading="commentLoading" class="comment-list">
        <div v-if="!comments.length && !commentLoading" class="comment-empty">还没有评论，快来抢沙发～</div>
        <div v-for="c in comments" :key="c.id" class="comment-item">
          <div class="comment-avatar">{{ (c.authorName || '?').slice(0, 1) }}</div>
          <div class="comment-body">
            <div class="comment-head">
              <span class="comment-author">{{ c.authorName }}</span>
              <span class="comment-time">{{ fmtTime(c.createTime) }}</span>
            </div>
            <div class="comment-content">{{ c.content }}</div>
            <div class="comment-ops">
              <span class="op" @click="doLikeComment(c)">赞 {{ c.likeCount || 0 }}</span>
              <span class="op" @click="toggleReply(c)">回复</span>
              <span v-if="isOwn(c)" class="op danger" @click="removeComment(c)">删除</span>
            </div>

            <div v-if="repliesMap[c.id]" class="reply-list">
              <div v-for="r in repliesMap[c.id]" :key="r.id" class="reply-item">
                <span class="reply-author">{{ r.authorName }}：</span>
                <span>{{ r.content }}</span>
                <span class="reply-meta">{{ fmtTime(r.createTime) }}</span>
              </div>
            </div>

            <div v-if="replyTarget && replyTarget.id === c.id" class="reply-input">
              <el-input v-model="replyText" type="textarea" :rows="2" maxlength="500" :placeholder="`回复 @${replyTarget.authorName}`" />
              <div class="comment-input-actions">
                <el-button size="small" @click="toggleReply(c)">取消</el-button>
                <el-button size="small" type="primary" @click="submitReply">回复</el-button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="comments.length < commentTotal" class="comment-more">
          <el-button text type="primary" @click="loadMoreComments">加载更多</el-button>
        </div>
      </div>
    </el-card>
    </template>
  </div>
</template>

<style scoped>
.nf {
  padding: 90px 0; display: flex; flex-direction: column;
  align-items: center; gap: 10px; text-align: center;
}
.nf-ic { font-size: 40px; color: var(--muted); }
.nf h2 { font-family: var(--serif); font-size: 20px; font-weight: 600; color: var(--ink); }
.nf p { font-size: 13px; color: var(--muted); margin-bottom: 10px; }
.offline-note {
  display: flex; align-items: center; gap: 6px;
  margin-top: 14px; padding: 9px 12px; border-radius: 6px;
  font-size: 13px; color: var(--ink-2);
  background: var(--paper-2); border: 1px solid var(--line);
}
.head-card :deep(.el-card__body) { padding: 28px; }
.head { display: flex; gap: 28px; }
.cover {
  width: 200px; height: 270px; border-radius: 10px; color: #fff;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
  font-family: var(--serif); font-size: 68px; font-weight: 600;
  position: relative; cursor: pointer;
  background: linear-gradient(160deg, var(--pf, #2b2733) 0%, var(--pt, #14121a) 100%);
}
.cover-img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; border-radius: 10px; }
.cover-open {
  position: absolute; width: 54px; height: 54px; border-radius: 50%;
  background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center;
  font-size: 22px; color: #fff; opacity: 0; transition: all .25s;
}
.cover:hover .cover-open { opacity: 1; }
.info { flex: 1; min-width: 0; }
.title-row { display: flex; align-items: center; gap: 12px; }
h1 { font-family: var(--serif); font-size: 26px; font-weight: 600; color: var(--ink); }
.tags { margin: 14px 0; display: flex; flex-wrap: wrap; gap: 8px; }
.intro { color: var(--ink-2); line-height: 1.9; margin-bottom: 18px; }
.stats { display: flex; gap: 36px; margin-bottom: 22px; }
.stat { display: flex; flex-direction: column; align-items: center; gap: 4px; }
.stat .v { font-family: var(--serif); font-size: 19px; font-weight: 600; color: var(--ink); }
.stat .v.stat-name { font-family: inherit; font-size: 15px; font-weight: 500; }
.stat .l { font-size: 12px; color: var(--muted); }
.author-link { color: var(--ink); cursor: pointer; border-bottom: 1px solid var(--line); }
.author-link:hover { color: var(--cinnabar); border-color: var(--cinnabar); }
.toc-header { display: flex; align-items: center; justify-content: space-between; }
.hint { font-size: 12px; color: var(--muted); }
.ch-no { font-weight: 600; }
.ch-words { font-size: 13px; color: var(--muted); }
.toc-pager { display: flex; align-items: center; justify-content: center; gap: 16px; flex-wrap: wrap; margin-top: 14px; }
.toc-pager .pager-info { font-size: 13px; color: #606266; font-weight: 600; }

/* ================= 评论 ================= */
.comment-input { margin-bottom: 18px; }
.comment-input-actions { display: flex; justify-content: flex-end; margin-top: 10px; }
.comment-list { min-height: 60px; }
.comment-empty { text-align: center; color: var(--muted); padding: 30px 0; }
.comment-item { display: flex; gap: 12px; padding: 14px 0; border-bottom: 1px solid var(--line-soft); }
.comment-item:last-of-type { border-bottom: none; }
.comment-avatar {
  width: 36px; height: 36px; border-radius: 50%; background: #f6e7e3; color: #b23a2e;
  display: flex; align-items: center; justify-content: center; font-weight: 700; flex-shrink: 0;
}
.comment-body { flex: 1; min-width: 0; }
.comment-head { display: flex; align-items: baseline; gap: 10px; }
.comment-author { font-weight: 600; color: var(--ink); }
.comment-time { font-size: 12px; color: var(--muted); }
.comment-content { margin: 6px 0 8px; line-height: 1.7; color: #3a3d4a; word-break: break-word; }
.comment-ops { display: flex; gap: 16px; }
.op { font-size: 13px; color: var(--muted); cursor: pointer; }
.op:hover { color: #b23a2e; }
.op.danger:hover { color: #f56c6c; }
.reply-list { margin-top: 10px; background: #f8f8fb; border-radius: 8px; padding: 8px 12px; }
.reply-item { font-size: 13px; padding: 4px 0; color: #3a3d4a; }
.reply-author { color: #b23a2e; font-weight: 600; }
.reply-meta { margin-left: 8px; font-size: 12px; color: var(--muted); }
.reply-input { margin-top: 10px; }
.comment-more { text-align: center; padding: 12px 0; }

/* 窄屏（手机）：.head 为 nowrap 的 flex，封面固定 200px 宽，
   在 390px 宽度下信息区仅剩 48px，标题与简介被逐字换行。
   改为上下堆叠、封面缩小居中。 */
@media (max-width: 768px) {
  .head { flex-direction: column; gap: 18px; }
  .cover { width: 130px; height: 176px; font-size: 46px; margin: 0 auto; }
  .info { width: 100%; }
  .head-card :deep(.el-card__body) { padding: 18px; }
  .title-row { flex-wrap: wrap; }
  h1 { font-size: 21px; }
  .stats { gap: 22px; flex-wrap: wrap; }
}
</style>
