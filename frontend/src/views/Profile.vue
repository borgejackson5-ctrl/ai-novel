<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getReadHistory, getMyNovels, changePassword, getUnreadCount } from '../api'
import { useUserStore } from '../store/user'
import { fmtTime } from '../utils/format'
import BookRow from '../components/BookRow.vue'
import { Bell, ChatDotRound, Collection, Document, EditPen, MagicStick, Setting } from '@element-plus/icons-vue'

const userStore = useUserStore()
const router = useRouter()
// 演示账号（测试号/管理员）：密码与账号信息锁定，仅提示不可改
const isDemoAccount = computed(() => ['admin', 'user'].includes(userStore.username))

const history = ref([])
const historyTotal = ref(0)
const works = ref([])
const worksTotal = ref(0)

// 仅取最近几条作为预览，完整记录在 /history（含时间筛选与分页），
// 首页不提供「展开全部」：查看完整历史应前往支持筛选与分页的页面
const HIST_PREVIEW = 6

// 作品审核状态：已通过是常态，不显示；其余状态需让作者看到
const auditText = (s) => ({ 0: '待审核', 2: '已拒绝', 3: '修改审核中' }[s] || '')

// ===== 账号设置（收进弹窗）=====
const accountVisible = ref(false)
const nicknameForm = ref({ nickname: '' })
const savingNickname = ref(false)
const saveNickname = async () => {
  const nickname = nicknameForm.value.nickname.trim()
  if (!nickname) {
    ElMessage.warning('昵称不能为空')
    return
  }
  if (nickname.length > 30) {
    ElMessage.warning('昵称最长 30 个字符')
    return
  }
  savingNickname.value = true
  try {
    await userStore.updateNickname(nickname)
    ElMessage.success('昵称已更新')
    nicknameForm.value.nickname = ''
  } catch (e) { /* 已在拦截器提示 */ } finally {
    savingNickname.value = false
  }
}

const pwdForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' })
const savingPwd = ref(false)
const savePassword = async () => {
  const { oldPassword, newPassword, confirmPassword } = pwdForm.value
  if (!oldPassword) {
    ElMessage.warning('请输入原密码')
    return
  }
  if (!newPassword || newPassword.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (newPassword !== confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  savingPwd.value = true
  try {
    await changePassword({ oldPassword, newPassword })
    ElMessage.success('密码已修改，请重新登录')
    userStore.clearSession()
    router.push('/login')
  } catch (e) { /* 已在拦截器提示 */ } finally {
    savingPwd.value = false
  }
}

// 未读数：显示在「我的消息」入口的副标题上，无需进入消息页即可获知未读条数
const unreadCount = ref(0)
const loadUnread = async () => {
  try {
    unreadCount.value = Number((await getUnreadCount()) || 0)
  } catch (e) { /* 未登录时忽略 */ }
}

// ===== 快捷入口（充值已独立成 /wallet，这里不再重复列出）=====
// 用 computed 而不是常量数组：其中一项的副标题要跟着未读数变
const entries = computed(() => [
  { icon: Collection, label: '我的书架', desc: '收藏与在架作品', to: '/shelf' },
  { icon: Document, label: '我的作品', desc: '投稿与审核状态', to: '/my-works' },
  {
    icon: Bell,
    label: '我的消息',
    desc: unreadCount.value > 0 ? `${unreadCount.value} 条未读` : '审核结果与反馈回复',
    to: '/messages'
  },
  { icon: EditPen, label: '创作发布', desc: '开一本新书', to: '/create' },
  { icon: MagicStick, label: 'AI 设置', desc: '模型与免费额度', to: '/settings' },
  { icon: ChatDotRound, label: '意见反馈', desc: '提建议得虚拟币', to: '/feedback' },
  { icon: Setting, label: '账号设置', desc: '昵称与密码', action: 'account' }
])

const onEntry = (e) => {
  if (e.action === 'account') accountVisible.value = true
  else router.push(e.to)
}

const loadHistory = async () => {
  try {
    const res = await getReadHistory({ pageNum: 1, pageSize: HIST_PREVIEW })
    history.value = res?.list || []
    historyTotal.value = Number(res?.total || 0)
  } catch (e) { /* 忽略 */ }
}
const loadWorks = async () => {
  try {
    const res = await getMyNovels({ pageNum: 1, pageSize: 4 })
    works.value = res?.list || []
    worksTotal.value = Number(res?.total || 0)
  } catch (e) { /* 忽略 */ }
}
const goChapter = (h) => router.push(`/novel/${h.novelId}/chapter/${h.chapterId}`)

onMounted(() => {
  userStore.fetchUserInfo()
  loadUnread()
  loadHistory()
  loadWorks()
})
</script>

<template>
  <div class="profile">
    <section class="user-card">
      <div class="avatar">{{ (userStore.nickname || userStore.username || 'U').slice(0, 1) }}</div>
      <div class="meta">
        <div class="nickname">{{ userStore.nickname || userStore.username }}</div>
        <div class="username">@{{ userStore.username }}</div>
      </div>
      <div class="balance">
        <div class="balance-num">{{ userStore.coinBalance }}</div>
        <div class="balance-label">虚拟币余额</div>
      </div>
      <el-button type="primary" @click="router.push('/wallet')">
        <el-icon><Coin /></el-icon><span>钱包</span>
      </el-button>
    </section>

    <section class="works-floor">
      <div class="floor-head">
        <h3 class="panel-title">我的作品</h3>
        <span class="floor-count">共 {{ worksTotal }} 部</span>
        <a class="floor-more" @click="router.push('/my-works')">全部作品 →</a>
      </div>

      <div v-if="!works.length" class="panel-empty">
        还没有发布过作品，<a class="link" @click="router.push('/create')">去创作 →</a>
      </div>
      <div v-else class="book-grid">
        <BookRow
          v-for="w in works"
          :key="w.id"
          :novel="w"
          :show-mine="false"
          :status-text="auditText(w.auditStatus)"
          :show-intro="false"
          @click="router.push(`/novel/${w.id}`)"
        >
          <template #action>
            <span class="link" @click="router.push(`/novel/${w.id}/manage`)">管理</span>
          </template>
        </BookRow>
      </div>
    </section>

    <div class="cols">
      <section class="panel">
        <div class="panel-head">
          <h3 class="panel-title">最近阅读</h3>
          <a v-if="historyTotal" class="panel-more" @click="router.push('/history')">全部记录 →</a>
        </div>
        <div v-if="!history.length" class="panel-empty">还没有阅读记录</div>
        <div v-else class="hist-list">
          <div v-for="h in history" :key="`${h.novelId}-${h.chapterId}`" class="hist-row" @click="goChapter(h)">
            <div class="hist-main">
              <div class="hist-title">{{ h.novelTitle }}</div>
              <div class="hist-meta">读到 第 {{ h.chapterNo }} 章 {{ h.chapterTitle }}</div>
            </div>
            <span class="hist-time">{{ fmtTime(h.createTime) }}</span>
          </div>
        </div>
      </section>

      <section class="panel">
        <h3 class="panel-title">快捷入口</h3>
        <div class="entries">
          <div v-for="e in entries" :key="e.label" class="entry" @click="onEntry(e)">
            <el-icon class="e-ic"><component :is="e.icon" /></el-icon>
            <div class="e-body">
              <div class="e-label">{{ e.label }}</div>
              <div class="e-desc">{{ e.desc }}</div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <el-dialog v-model="accountVisible" title="账号设置" width="520px">
      <el-alert v-if="isDemoAccount" type="info" :closable="false" show-icon
        title="演示账号：密码和账号信息不可修改" style="margin-bottom: 18px" />

      <div class="acct-section">
        <div class="acct-title">修改昵称 / 笔名</div>
        <div class="acct-row">
          <el-input v-model="nicknameForm.nickname" placeholder="新昵称（最长 30 字）" maxlength="30" clearable :disabled="isDemoAccount" />
          <el-button type="primary" :loading="savingNickname" :disabled="isDemoAccount" @click="saveNickname">保存</el-button>
        </div>
      </div>

      <el-divider />

      <div class="acct-section">
        <div class="acct-title">修改密码</div>
        <div class="acct-row">
          <el-input v-model="pwdForm.oldPassword" type="password" placeholder="原密码" show-password :disabled="isDemoAccount" />
        </div>
        <div class="acct-row">
          <el-input v-model="pwdForm.newPassword" type="password" placeholder="新密码（6-32 位）" show-password :disabled="isDemoAccount" />
        </div>
        <div class="acct-row">
          <el-input v-model="pwdForm.confirmPassword" type="password" placeholder="确认新密码" show-password :disabled="isDemoAccount" />
        </div>
        <el-button type="danger" :loading="savingPwd" :disabled="isDemoAccount" @click="savePassword">
          修改密码（需重新登录）
        </el-button>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.profile { max-width: 1000px; }

.user-card {
  display: flex; align-items: center; gap: 18px;
  padding: 24px 26px;
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 12px;
}
.avatar {
  width: 60px; height: 60px; border-radius: 50%;
  background: var(--cinnabar); color: #fff;
  font-size: 24px; font-weight: 500;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.meta { min-width: 0; }
.nickname { font-family: var(--serif); font-size: 20px; font-weight: 600; color: var(--ink); }
.username { color: var(--muted); font-size: 13px; margin-top: 4px; }
.balance { margin-left: auto; text-align: right; padding-right: 8px; }
.balance-num { font-family: var(--serif); font-size: 30px; font-weight: 600; color: #8a6a20; line-height: 1.1; }
.balance-label { color: var(--muted); font-size: 12.5px; margin-top: 2px; }

.cols { display: grid; grid-template-columns: 1.25fr 1fr; gap: 20px; margin-top: 20px; }

.works-floor {
  margin-top: 20px; padding: 18px 20px;
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 12px;
}
.floor-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 14px; }
.floor-head .panel-title { margin-bottom: 0; }
.floor-count { font-size: 12.5px; color: var(--muted); }
.floor-more { margin-left: auto; font-size: 13px; color: var(--cinnabar); cursor: pointer; }
.floor-more:hover { text-decoration: underline; }
.link { color: var(--cinnabar); cursor: pointer; }
.link:hover { text-decoration: underline; }
.panel {
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 12px;
  padding: 18px 20px;
}
.panel-title {
  font-family: var(--serif); font-size: 16px; font-weight: 600; color: var(--ink);
  margin-bottom: 14px;
}
.panel-empty { color: var(--muted); font-size: 13px; padding: 20px 0; text-align: center; }

.hist-list { display: flex; flex-direction: column; }
.hist-row {
  display: flex; align-items: center; gap: 12px;
  padding: 11px 8px; border-radius: 8px; cursor: pointer;
  border-bottom: 1px solid var(--line-soft);
  transition: background .18s;
}
.hist-row:last-child { border-bottom: none; }
.hist-row:hover { background: var(--paper); }
.hist-main { flex: 1; min-width: 0; }
.hist-title { font-size: 14px; font-weight: 500; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-meta { font-size: 12px; color: var(--muted); margin-top: 3px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-time { font-size: 12px; color: var(--muted); flex-shrink: 0; }
.panel-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 14px; }
.panel-head .panel-title { margin-bottom: 0; }
.panel-more { margin-left: auto; font-size: 13px; color: var(--cinnabar); cursor: pointer; }
.panel-more:hover { text-decoration: underline; }

.entries { display: flex; flex-direction: column; }
.entry {
  display: flex; align-items: center; gap: 12px;
  padding: 11px 8px; border-radius: 8px; cursor: pointer;
  border-bottom: 1px solid var(--line-soft);
  transition: background .18s;
}
.entry:last-child { border-bottom: none; }
.entry:hover { background: var(--paper); }
.e-ic { font-size: 17px; color: var(--cinnabar); flex-shrink: 0; }
.e-body { min-width: 0; }
.e-label { font-size: 14px; color: var(--ink); }
.e-desc { font-size: 12px; color: var(--muted); margin-top: 2px; }

.acct-section { display: flex; flex-direction: column; gap: 12px; }
.acct-title { font-size: 14px; font-weight: 500; color: var(--ink); }
.acct-row { display: flex; gap: 10px; }
.acct-row .el-input { flex: 1; }

@media (max-width: 900px) {
  /* 使用 minmax(0, 1fr) 而非 1fr：1fr 等价于 minmax(auto, 1fr)，
     min 为 auto 时列宽会被内容的最小宽度撑开。390px 宽度下 .panel 被撑到 417px、
     整页横向滚动 41px。min 设为 0 才会真正跟随容器宽度。 */
  .cols { grid-template-columns: minmax(0, 1fr); }
  .user-card { flex-wrap: wrap; }
  .balance { margin-left: 0; }
}
</style>
