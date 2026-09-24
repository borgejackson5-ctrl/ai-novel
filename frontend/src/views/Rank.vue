<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getRank } from '../api'
import BookRow from '../components/BookRow.vue'

const router = useRouter()
const list = ref([])
const loading = ref(false)

const TABS = [
  { code: 'hot', label: '热门榜', hint: '按实时阅读热度排序' },
  { code: 'new', label: '新书榜', hint: '按上架先后排序' },
  { code: 'finished', label: '完本榜', hint: '只看已完结的作品' },
  { code: 'collect', label: '收藏榜', hint: '按加入书架数排序' }
]
const tab = ref('hot')
const tabHint = () => TABS.find((t) => t.code === tab.value)?.hint || ''

const load = async () => {
  loading.value = true
  try {
    list.value = (await getRank(tab.value)) || []
  } catch (e) { /* 限流/网络错误已在拦截器提示 */ } finally {
    loading.value = false
  }
}

const switchTab = (code) => {
  if (tab.value === code) return
  tab.value = code
  list.value = []
  load()
}

const goDetail = (id) => router.push(`/novel/${id}`)

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="section-title">排行榜</h2>
      <span class="count">{{ tabHint() }} · 共 {{ list.length }} 部</span>
    </div>

    <div class="rank-tabs">
      <span
        v-for="t in TABS"
        :key="t.code"
        class="rank-tab"
        :class="{ active: tab === t.code }"
        @click="switchTab(t.code)"
      >{{ t.label }}</span>
    </div>

    <div class="book-grid" v-loading="loading">
      <BookRow
        v-for="(d, i) in list"
        :key="d.id"
        :novel="d"
        :rank="i + 1"
        :status-text="d.serialStatus === 1 ? d.serialStatusText : ''"
        @click="goDetail(d.id)"
      />
    </div>

    <div v-if="!loading && !list.length" class="empty-tip">榜单暂时没有数据</div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 18px;
}
.page-head .section-title { margin-bottom: 0; }
.count { color: var(--muted); font-size: 13px; }
.rank-tabs {
  display: flex;
  gap: 22px;
  margin-bottom: 18px;
  border-bottom: 1px solid var(--line);
}
.rank-tab {
  position: relative;
  padding-bottom: 9px;
  font-size: 14px;
  color: var(--muted);
  cursor: pointer;
  transition: color .18s;
}
.rank-tab:hover { color: var(--ink); }
.rank-tab.active { color: var(--ink); font-weight: 500; }
.rank-tab.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--cinnabar);
  border-radius: 1px;
}
</style>
