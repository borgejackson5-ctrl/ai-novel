<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMyNovels, offlineMyNovel, reshelveMyNovel, deleteMyNovel, finishMyNovel, resumeSerialMyNovel } from '../api'
import { posterVars, posterChar, fmtWan } from '../utils/cover'
import { fmtTime } from '../utils/format'
import { CircleCheck, CircleClose, Clock, Refresh } from '@element-plus/icons-vue'

const router = useRouter()
const loading = ref(true)
const list = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 12

// 审核状态展示
const auditMap = {
  0: { text: '待审核', tag: 'warning', icon: Clock },
  1: { text: '已通过', tag: 'success', icon: CircleCheck },
  2: { text: '已拒绝', tag: 'danger', icon: CircleClose },
  3: { text: '修改审核中', tag: 'warning', icon: Refresh },
  4: { text: '重新上架待审', tag: 'warning', icon: Refresh }
}
const auditOf = (s) => auditMap[s] || auditMap[0]

// 上下架状态：未过审/被拒的作品还没有「上架」这个概念，返回 null 不展示
const shelfOf = (w) => {
  if (w.auditStatus === 4) return { text: '重新上架审核中', tag: 'warning' }
  if (w.status === 1) return { text: '已上架', tag: 'success' }
  if (w.auditStatus === 1) return { text: '已下架', tag: 'info' }
  return null
}

const load = async () => {
  loading.value = true
  try {
    const res = await getMyNovels({ pageNum: pageNum.value, pageSize })
    list.value = res?.list || []
    total.value = Number(res?.total || 0)
  } finally {
    loading.value = false
  }
}

const onPage = (p) => {
  pageNum.value = p
  load()
}

// 下架 / 重新上架 / 删除：门槛原因由后端算好下发（offlineBlockedReason 等），
// 前端据此置灰按钮并用 tooltip 说明，不做任何时间判断。
const doOffline = async (w) => {
  try {
    await ElMessageBox.confirm(
      `下架后《${w.title}》将不再出现在书库、榜单与搜索中；已加入书架的读者仍可阅读已解锁章节。`,
      '下架作品',
      { type: 'warning', confirmButtonText: '确认下架', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await offlineMyNovel(w.id)
    ElMessage.success('已下架')
    load()
  } catch (e) { /* 保护期不足等已在拦截器提示 */ }
}

const doReshelve = async (w) => {
  try {
    await ElMessageBox.confirm(
      `申请后《${w.title}》需要审核，通过后恢复上架。`,
      '申请重新上架',
      { type: 'info', confirmButtonText: '提交申请', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await reshelveMyNovel(w.id)
    ElMessage.success('已提交重新上架申请')
    load()
  } catch (e) { /* 冷却期不足等已在拦截器提示 */ }
}

const doDelete = async (w) => {
  try {
    await ElMessageBox.confirm(
      `删除后《${w.title}》不可恢复。已有读者付费解锁过的作品不允许删除；`
        + `删除后书架里的条目会显示为「已删除」，届时无法再阅读。`,
      '删除作品',
      { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await deleteMyNovel(w.id)
    ElMessage.success('作品已删除')
    load()
  } catch (e) { /* 门槛不足等已在拦截器提示 */ }
}

// 标记完结为单向操作，确认框需明确说明「之后修改内容需先提交申请」，
// 否则作者确认后才发现章节不可修改，只能通过客服解决
const doFinish = async (w) => {
  try {
    await ElMessageBox.confirm(
      `标记为已完结后，《${w.title}》的章节与简介、标签等内容将无法再修改（书名和封面仍可改）。`
        + `如果之后想恢复连载，需要向管理员提交申请，且 3 天内不能申请。`,
      '标记为已完结',
      { type: 'warning', confirmButtonText: '确认完结', cancelButtonText: '再想想' }
    )
  } catch (e) {
    return
  }
  try {
    await finishMyNovel(w.id)
    ElMessage.success('已标记为已完结')
    load()
  } catch (e) { /* 已在拦截器提示 */ }
}

// 申请恢复连载：理由选填，交由管理员判断
const doResumeSerial = async (w) => {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt(
      `《${w.title}》已完结。恢复连载需要管理员审核，审核通过后章节才能继续更新。`,
      '申请恢复连载',
      {
        inputPlaceholder: '简单说明一下为什么要恢复（选填）',
        inputValue: '',
        confirmButtonText: '提交申请',
        cancelButtonText: '取消',
        inputValidator: (v) => !v || v.length <= 500 || '最多 500 字'
      }
    )
    reason = value || ''
  } catch (e) {
    return
  }
  try {
    await resumeSerialMyNovel(w.id, { reason })
    ElMessage.success('已提交申请，等待管理员处理')
    load()
  } catch (e) { /* 冷静期内等已在拦截器提示 */ }
}

onMounted(load)
</script>

<template>
  <div class="works-page">
    <div class="page-head">
      <h2 class="section-title">我的作品</h2>
      <el-button type="primary" round @click="router.push('/create')"
        >＋ 新的创作</el-button
      >
    </div>

    <div v-loading="loading" class="works-body">
      <div v-if="!list.length && !loading" class="empty">
        <div class="empty-ic"><el-icon><Collection /></el-icon></div>
        <div class="empty-t">还没有发布过作品</div>
        <div class="empty-s">写下你的第一章，发布第一部小说吧</div>
        <el-button type="primary" round @click="router.push('/create')">去创作</el-button>
      </div>

      <div v-else class="works-grid">
        <div v-for="w in list" :key="w.id" class="work-card">
          <div class="poster" :style="posterVars(w)" @click="router.push(`/novel/${w.id}`)">
            <img v-if="w.coverUrl" :src="w.coverUrl" class="poster-img" alt="封面" />
            <template v-else>
              <span class="poster-char">{{ posterChar(w.title) }}</span>
            </template>
            <span class="read-mask"><span class="read-btn"><el-icon><Reading /></el-icon></span></span>
          </div>

          <div class="work-info">
            <div class="work-title-row">
              <span class="work-title">{{ w.title }}</span>
              <el-tag :type="auditOf(w.auditStatus).tag" size="small" effect="light" round>
                <el-icon><component :is="auditOf(w.auditStatus).icon" /></el-icon> {{ auditOf(w.auditStatus).text }}
              </el-tag>
              <el-tag v-if="shelfOf(w)" :type="shelfOf(w).tag" size="small" effect="plain" round>
                {{ shelfOf(w).text }}
              </el-tag>
              <el-tag
                v-if="w.serialStatusText"
                :type="w.serialStatus === 1 ? 'info' : ''"
                size="small"
                effect="plain"
                round
              >
                {{ w.appealPending ? '恢复连载审核中' : w.serialStatusText }}
              </el-tag>
            </div>
            <div class="work-meta">
              <span class="cat">{{ w.categoryName || '未分类' }}</span>
              <span>{{ w.totalChapters }} 章</span>
              <span>{{ fmtWan(w.wordCount) }} 字</span>
              <span>阅读 {{ fmtWan(w.readCount) }}</span>
              <span>点赞 {{ fmtWan(w.likeCount) }}</span>
              <span>{{ fmtTime(w.createTime, 10) }}</span>
            </div>
            <el-tooltip
              v-if="w.auditStatus === 2 && w.auditResult"
              :content="'拒绝原因：' + w.auditResult"
              placement="top"
            >
              <div class="reject-reason">{{ w.auditResult }}</div>
            </el-tooltip>
            <el-tooltip
              v-else-if="w.auditStatus === 0"
              :content="w.auditResult || 'AI 预审 + 管理员人工终审中'"
              placement="top"
            >
              <div class="wait-tip">{{ w.auditResult || '审核中，请耐心等待' }}</div>
            </el-tooltip>
            <div class="work-actions">
              <el-button
                link
                type="primary"
                size="small"
                @click="router.push(`/novel/${w.id}/manage`)"
              ><el-icon><Setting /></el-icon><span>管理</span></el-button>
              <el-button
                link
                type="primary"
                size="small"
                @click="router.push(`/novel/${w.id}`)"
              >去阅读 ›</el-button>

              <template v-if="w.status === 1">
                <el-tooltip
                  :content="w.offlineBlockedReason"
                  :disabled="!w.offlineBlockedReason"
                  placement="top"
                >
                  <span class="act-wrap">
                    <el-button
                      link
                      type="warning"
                      size="small"
                      :disabled="!!w.offlineBlockedReason"
                      @click="doOffline(w)"
                    ><el-icon><SwitchButton /></el-icon><span>下架</span></el-button>
                  </span>
                </el-tooltip>

                <el-tooltip
                  v-if="w.serialStatus === 1"
                  :content="w.resumeSerialBlockedReason"
                  :disabled="!w.resumeSerialBlockedReason"
                  placement="top"
                >
                  <span class="act-wrap">
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :disabled="!!w.resumeSerialBlockedReason"
                      @click="doResumeSerial(w)"
                    ><el-icon><Refresh /></el-icon><span>申请恢复连载</span></el-button>
                  </span>
                </el-tooltip>
                <el-tooltip
                  v-else
                  content="标记后章节与简介等内容将锁定，仅可改书名与封面"
                  placement="top"
                >
                  <span class="act-wrap">
                    <el-button
                      link
                      type="success"
                      size="small"
                      @click="doFinish(w)"
                    ><el-icon><Select /></el-icon><span>标记完结</span></el-button>
                  </span>
                </el-tooltip>
              </template>

              <template v-else-if="w.auditStatus === 1">
                <el-tooltip
                  :content="w.reshelveBlockedReason"
                  :disabled="!w.reshelveBlockedReason"
                  placement="top"
                >
                  <span class="act-wrap">
                    <el-button
                      link
                      type="primary"
                      size="small"
                      :disabled="!!w.reshelveBlockedReason"
                      @click="doReshelve(w)"
                    ><el-icon><Refresh /></el-icon><span>申请重新上架</span></el-button>
                  </span>
                </el-tooltip>
                <el-tooltip
                  :content="w.deleteBlockedReason"
                  :disabled="!w.deleteBlockedReason"
                  placement="top"
                >
                  <span class="act-wrap">
                    <el-button
                      link
                      type="danger"
                      size="small"
                      :disabled="!!w.deleteBlockedReason"
                      @click="doDelete(w)"
                    ><el-icon><Delete /></el-icon><span>删除</span></el-button>
                  </span>
                </el-tooltip>
              </template>
            </div>
          </div>
        </div>
      </div>

      <div v-if="total > pageSize" class="pager">
        <el-pagination
          layout="prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="pageNum"
          @current-change="onPage"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.works-page {
  max-width: 1200px;
  margin: 0 auto;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}
.page-head .section-title {
  margin-bottom: 0;
}
.works-body {
  min-height: 300px;
}
.empty {
  padding: 70px 0;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.empty-ic {
  font-size: 48px;
  color: var(--line);
}
.empty-t {
  font-size: 17px;
  font-weight: 700;
}
.empty-s {
  color: var(--muted);
  font-size: 13.5px;
  margin-bottom: 8px;
}
.works-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 18px;
}
.work-card {
  background: #fff;
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 4px 16px rgba(30, 26, 60, 0.07);
  transition: all 0.2s;
}
.work-card:hover {
  transform: translateY(-2px);
  border-color: var(--line);
}
.poster {
  position: relative;
  overflow: hidden;
  aspect-ratio: 3 / 4;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  background: linear-gradient(160deg, var(--pf), var(--pt));
}
.poster-img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.poster-char {
  font-family: var(--serif);
  font-size: 70px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.92);
  z-index: 1;
}
.read-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0);
  transition: background 0.2s;
  z-index: 2;
}
.poster:hover .read-mask {
  background: rgba(0, 0, 0, 0.35);
}
.read-btn {
  font-size: 30px;
  width: 58px;
  height: 58px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  border: 2px solid rgba(255, 255, 255, 0.75);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transform: scale(0.8);
  transition: all 0.2s;
}
.poster:hover .read-btn {
  opacity: 1;
  transform: scale(1);
}
.work-info {
  padding: 12px 14px 14px;
}
.work-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.work-title {
  font-weight: 700;
  font-size: 15px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.work-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--muted);
  font-size: 12.5px;
  margin-top: 8px;
}
.cat {
  color: var(--primary);
  background: rgba(178, 58, 46, 0.09);
  padding: 1px 8px;
  border-radius: 10px;
}
.work-actions {
  margin-top: 10px;
  text-align: right;
}
/* el-tooltip 包裹被禁用的按钮时需要一层可接收鼠标事件的容器，否则 hover 不出提示 */
.act-wrap {
  display: inline-flex;
  margin-left: 8px;
}
.reject-reason {
  margin-top: 8px;
  font-size: 12.5px;
  color: #d0455a;
  background: rgba(208, 69, 90, 0.07);
  border-radius: 8px;
  padding: 6px 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: help;
}
.wait-tip {
  margin-top: 8px;
  font-size: 12.5px;
  color: #b8862b;
  background: rgba(247, 186, 42, 0.1);
  border-radius: 8px;
  padding: 6px 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: help;
}
.pager {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}
</style>
