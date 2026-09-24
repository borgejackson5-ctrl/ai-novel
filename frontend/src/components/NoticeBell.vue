<script setup>
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { fmtTime } from '../utils/format'
import {
  getMessagePage,
  getUnreadCount,
  markMessageRead,
  markAllMessageRead
} from '../api'
import { messageIcon, messageRoute } from '../utils/message'

const router = useRouter()
const userStore = useUserStore()
const unread = ref(0)
const visible = ref(false)
const list = ref([])
const loading = ref(false)
let timer = null

const isLoggedIn = () => !!userStore.token

const refreshCount = async () => {
  if (!isLoggedIn()) return
  try {
    unread.value = (await getUnreadCount()) || 0
  } catch (e) {
    /* 静默失败，不弹出提示 */
  }
}

const loadList = async () => {
  loading.value = true
  try {
    const res = await getMessagePage({ pageNum: 1, pageSize: 8 })
    list.value = res?.list || []
  } finally {
    loading.value = false
  }
}

const onOpen = () => {
  visible.value = true
  loadList()
}

const onItemClick = async (m) => {
  if (!m.isRead) {
    try {
      await markMessageRead(m.id)
      m.isRead = 1
      unread.value = Math.max(0, unread.value - 1)
    } catch (e) {
      /* 忽略 */
    }
  }
  // 去向统一由 utils/message 决定：此前此处与 Messages.vue 各有一份实现，
  // 均只识别 AUDIT_* 三种，导致「反馈奖励到账」「申请处理结果」点击后无反应
  const to = messageRoute(m.type, userStore.role)
  if (to) {
    router.push(to)
    visible.value = false
  }
}

const onReadAll = async () => {
  try {
    await markAllMessageRead()
    list.value.forEach((m) => (m.isRead = 1))
    unread.value = 0
  } catch (e) {
    /* 忽略 */
  }
}

// 铃铛中仅展示最近 8 条，更早的消息在「全部消息」页查看
// 管理员无法进入前台，落点按角色区分，否则该入口对管理员无效
const allMessagesPath = computed(() => (userStore.role === 'admin' ? '/admin/messages' : '/messages'))
const goAll = () => {
  visible.value = false
  router.push(allMessagesPath.value)
}

onMounted(() => {
  refreshCount()
  timer = setInterval(refreshCount, 20000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <el-popover placement="bottom" :width="360" trigger="click" @show="onOpen">
    <template #reference>
      <div class="bell-wrap">
        <el-icon class="bell"><Bell /></el-icon>
        <span v-if="unread > 0" class="bell-dot">{{ unread > 99 ? '99+' : unread }}</span>
      </div>
    </template>
    <div class="notice-panel">
      <div class="notice-head">
        <span>消息通知</span>
        <el-button v-if="unread > 0" link type="primary" size="small" @click="onReadAll"
          >全部已读</el-button
        >
      </div>
      <div v-loading="loading" class="notice-list">
        <div v-if="!list.length && !loading" class="notice-empty">暂无消息</div>
        <div
          v-for="m in list"
          :key="m.id"
          class="notice-item"
          :class="{ unread: !m.isRead }"
          @click="onItemClick(m)"
        >
          <el-icon class="ni-icon"><component :is="messageIcon(m.type)" /></el-icon>
          <div class="ni-main">
            <div class="ni-title">
              {{ m.title }}
              <i v-if="!m.isRead" class="ni-dot" />
            </div>
            <div class="ni-content">{{ m.content }}</div>
            <div class="ni-time">{{ fmtTime(m.createTime) }}</div>
          </div>
        </div>
      </div>
      <div class="notice-foot" @click="goAll">
        全部消息<span v-if="unread">（{{ unread }} 条未读）</span> →
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.bell-wrap {
  position: relative;
  cursor: pointer;
  display: flex;
  align-items: center;
  padding: 4px 2px;
}
.bell {
  font-size: 18px;
  color: var(--ink-2, #3a352e);
}
.bell-dot {
  position: absolute;
  top: -2px;
  right: -8px;
  background: #ff4d4f;
  color: #fff;
  border-radius: 10px;
  font-size: 10px;
  line-height: 1;
  padding: 2px 5px;
  font-weight: 600;
  min-width: 14px;
  text-align: center;
}
.notice-panel {
  margin: -4px -8px;
}
.notice-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 12px 10px;
  border-bottom: 1px solid var(--line-soft, #efeade);
  font-weight: 600;
  font-size: 14px;
}
.notice-list {
  max-height: 340px;
  overflow: auto;
}
.notice-foot {
  padding: 10px 12px;
  text-align: center;
  font-size: 13px;
  color: var(--cinnabar, #9e2b25);
  cursor: pointer;
  border-top: 1px solid var(--line-soft, #efeade);
}
.notice-foot:hover {
  background: var(--paper, #f7f4ed);
  text-decoration: underline;
}
.notice-empty {
  padding: 28px 0;
  text-align: center;
  color: var(--muted, #8a7f6d);
  font-size: 13px;
}
.notice-item {
  display: flex;
  gap: 10px;
  padding: 10px 12px;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid var(--line-soft, #efeade);
}
.notice-item:hover {
  background: var(--paper, #f7f4ed);
}
.notice-item.unread .ni-title {
  font-weight: 600;
}
.ni-icon {
  font-size: 17px;
  margin-top: 2px;
  color: var(--cinnabar, #b23a2e);
}
.ni-main {
  flex: 1;
  min-width: 0;
}
.ni-title {
  font-size: 13.5px;
  color: var(--ink);
  display: flex;
  align-items: center;
  gap: 6px;
}
.ni-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #ff4d4f;
  flex-shrink: 0;
}
.ni-content {
  font-size: 12.5px;
  color: #7c818e;
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.ni-time {
  font-size: 11.5px;
  color: #b3b8c2;
  margin-top: 4px;
}
</style>
