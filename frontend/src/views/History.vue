<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getReadHistory } from '../api'
import { fmtTime } from '../utils/format'

const router = useRouter()
const list = ref([])
const total = ref(0)
const loading = ref(false)
const range = ref(null)
const pageNum = ref(1)
const pageSize = 20

// 日期区间快捷项（含首含尾，后端按「结束日 +1 天」换算成左闭右开区间）
const dateShortcuts = [
  {
    text: '近 7 天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setDate(start.getDate() - 6)
      return [start, end]
    }
  },
  {
    text: '近 30 天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setDate(start.getDate() - 29)
      return [start, end]
    }
  }
]

const hasFilter = computed(() => Array.isArray(range.value) && range.value.length === 2)
const emptyText = computed(() => (hasFilter.value ? '这段时间没有阅读记录' : '还没有阅读记录'))

const load = async (page = 1) => {
  pageNum.value = page
  loading.value = true
  const params = { pageNum: page, pageSize }
  if (hasFilter.value) {
    params.startDate = range.value[0]
    params.endDate = range.value[1]
  }
  try {
    const res = await getReadHistory(params)
    list.value = res?.list || []
    total.value = Number(res?.total || 0)
  } catch (e) { /* 已在拦截器提示 */ } finally {
    loading.value = false
  }
}

const goChapter = (h) => router.push(`/novel/${h.novelId}/chapter/${h.chapterId}`)

onMounted(() => load(1))
</script>

<template>
  <div class="history">
    <div class="page-head">
      <h2 class="section-title">阅读记录</h2>
      <span class="count">共 {{ total }} 部</span>
      <div class="head-right">
        <el-date-picker
          v-model="range"
          type="daterange"
          size="small"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :shortcuts="dateShortcuts"
          @change="load(1)"
        />
        <a class="more" @click="router.push('/shelf')">我的书架 →</a>
      </div>
    </div>

    <div class="panel" v-loading="loading">
      <div v-if="!loading && !list.length" class="panel-empty">{{ emptyText }}</div>
      <div v-else class="hist-list">
        <div v-for="h in list" :key="`${h.novelId}-${h.chapterId}`" class="hist-row" @click="goChapter(h)">
          <div class="hist-main">
            <div class="hist-title">{{ h.novelTitle }}</div>
            <div class="hist-meta">读到 第 {{ h.chapterNo }} 章 {{ h.chapterTitle }}</div>
          </div>
          <span class="hist-time">{{ fmtTime(h.createTime) }}</span>
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
.history { max-width: 1000px; }

.page-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 18px; flex-wrap: wrap; }
.page-head .section-title { margin-bottom: 0; }
.count { color: var(--muted); font-size: 13px; }
.head-right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.more {
  font-size: 13px; color: var(--cinnabar); cursor: pointer; white-space: nowrap;
}
.more:hover { text-decoration: underline; }

.panel {
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 12px;
  padding: 14px 20px;
}
.panel-empty { color: var(--muted); font-size: 13px; padding: 44px 0; text-align: center; }

.hist-list { display: flex; flex-direction: column; }
.hist-row {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 8px; border-radius: 8px; cursor: pointer;
  border-bottom: 1px solid var(--line-soft);
  transition: background .18s;
}
.hist-row:last-child { border-bottom: none; }
.hist-row:hover { background: var(--paper); }
.hist-main { flex: 1; min-width: 0; }
.hist-title { font-size: 14.5px; font-weight: 500; color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-meta { font-size: 12.5px; color: var(--muted); margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-time { font-size: 12px; color: var(--muted); flex-shrink: 0; }

.pager { margin-top: 22px; justify-content: center; }

@media (max-width: 900px) {
  .head-right { margin-left: 0; }
}
</style>
