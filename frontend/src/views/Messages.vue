<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { messageIcon, messageRoute } from '../utils/message'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getMessagePage,
  getUnreadCount,
  markMessageRead,
  markAllMessageRead,
  clearReadMessages
} from '../api'
import { fmtTime } from '../utils/format'

const router = useRouter()
const userStore = useUserStore()
const list = ref([])
const total = ref(0)
const unread = ref(0)
const loading = ref(false)
const status = ref('')
const pageNum = ref(1)
const pageSize = 20

const emptyText = computed(() => (status.value === 0 ? '没有未读消息' : status.value === 1 ? '没有已读消息' : '还没有收到消息'))

const load = async (page = 1) => {
  pageNum.value = page
  loading.value = true
  const params = { pageNum: page, pageSize }
  if (status.value !== '') params.isRead = status.value
  try {
    const res = await getMessagePage(params)
    list.value = res?.list || []
    total.value = Number(res?.total || 0)
  } catch (e) { /* 已在拦截器提示 */ } finally {
    loading.value = false
  }
}

const loadUnread = async () => {
  try {
    unread.value = Number((await getUnreadCount()) || 0)
  } catch (e) { /* 忽略 */ }
}

const openMessage = async (m) => {
  if (!m.isRead) {
    try {
      await markMessageRead(m.id)
      m.isRead = 1
      unread.value = Math.max(0, unread.value - 1)
    } catch (e) { /* 忽略 */ }
  }
  // 去向统一由 utils/message 决定（与铃铛共用一份映射）
  const to = messageRoute(m.type, userStore.role)
  if (to) router.push(to)
}

const onReadAll = async () => {
  try {
    await markAllMessageRead()
    ElMessage.success('已全部标记为已读')
    await Promise.all([load(1), loadUnread()])
  } catch (e) { /* 已在拦截器提示 */ }
}

const onClearRead = async () => {
  try {
    await ElMessageBox.confirm('将清空所有已读消息，未读消息不会受影响。', '清空已读消息', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    const cleared = await clearReadMessages()
    ElMessage.success(cleared ? `已清空 ${cleared} 条已读消息` : '没有可清空的已读消息')
    await Promise.all([load(1), loadUnread()])
  } catch (e) { /* 已在拦截器提示 */ }
}

onMounted(() => {
  load(1)
  loadUnread()
})
</script>

<template>
  <div class="messages">
    <div class="page-head">
      <h2 class="section-title">消息通知</h2>
      <span class="count">
        共 {{ total }} 条<span v-if="unread"> · {{ unread }} 条未读</span>
      </span>
      <div class="head-right">
        <el-select v-model="status" class="status-filter" size="small" @change="load(1)">
          <el-option label="全部消息" value="" />
          <el-option label="未读" :value="0" />
          <el-option label="已读" :value="1" />
        </el-select>
        <el-button v-if="unread > 0" size="small" @click="onReadAll">全部已读</el-button>
        <el-button size="small" :disabled="!list.length" @click="onClearRead">清空已读</el-button>
      </div>
    </div>

    <div class="panel" v-loading="loading">
      <div v-if="!loading && !list.length" class="panel-empty">{{ emptyText }}</div>
      <div v-else class="msg-list">
        <div
          v-for="m in list"
          :key="m.id"
          class="msg-row"
          :class="{ unread: !m.isRead }"
          @click="openMessage(m)"
        >
          <el-icon class="msg-icon"><component :is="messageIcon(m.type)" /></el-icon>
          <div class="msg-main">
            <div class="msg-title">
              {{ m.title }}
              <span class="msg-tag" :class="m.isRead ? 'is-read' : 'is-unread'">
                {{ m.isRead ? '已读' : '未读' }}
              </span>
            </div>
            <div class="msg-content">{{ m.content }}</div>
          </div>
          <span class="msg-time">{{ fmtTime(m.createTime) }}</span>
        </div>
      </div>
    </div>

    <el-pagination
      v-if="total > pageSize"
      class="pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="pageNum"
      @current-change="load"
    />
  </div>
</template>

<style scoped>
.messages { max-width: 1000px; }

.page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; flex-wrap: wrap; }
.page-head .section-title { margin-bottom: 0; }
.count { color: var(--muted); font-size: 13px; }
.head-right { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.status-filter { width: 116px; }

.panel {
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 12px;
  padding: 8px 20px;
}
.panel-empty { color: var(--muted); font-size: 13px; padding: 48px 0; text-align: center; }

.msg-list { display: flex; flex-direction: column; }
.msg-row {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 15px 8px; border-radius: 8px; cursor: pointer;
  border-bottom: 1px solid var(--line-soft);
  transition: background .18s;
}
.msg-row:last-child { border-bottom: none; }
.msg-row:hover { background: var(--paper); }
.msg-icon { font-size: 17px; color: var(--muted); margin-top: 2px; flex-shrink: 0; }
.msg-row.unread .msg-icon { color: var(--cinnabar); }
.msg-main { flex: 1; min-width: 0; }
.msg-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 14.5px; color: var(--ink); font-weight: 500;
}
.msg-tag {
  font-size: 11px; font-weight: 400; padding: 1px 6px;
  border-radius: 4px; flex-shrink: 0;
}
.msg-tag.is-unread { color: var(--cinnabar); background: var(--cinnabar-bg); }
.msg-tag.is-read { color: var(--muted); border: 1px solid var(--line); }
.msg-content { font-size: 13px; color: var(--muted); margin-top: 5px; line-height: 1.6; }
.msg-time { font-size: 12px; color: var(--muted); flex-shrink: 0; }

.pager { margin-top: 22px; justify-content: center; }

@media (max-width: 900px) {
  .head-right { margin-left: 0; }
}
</style>
