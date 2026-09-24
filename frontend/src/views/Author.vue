<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getNovelsByAuthor } from '../api'
import BookRow from '../components/BookRow.vue'

const route = useRoute()
const router = useRouter()

const list = ref([])
const total = ref(0)
const loading = ref(false)
const pageNum = ref(1)
const pageSize = 12

const authorId = computed(() => route.params.id)
// 后端没有独立的作者资料接口，笔名从作品数据里取（同一作者的作品笔名一致）
const authorName = computed(() => list.value[0]?.author || '')

const load = async () => {
  loading.value = true
  try {
    const res = await getNovelsByAuthor(authorId.value, { pageNum: pageNum.value, pageSize })
    list.value = res?.list || []
    total.value = Number(res?.total || 0)
  } catch (e) { /* 已在拦截器提示 */ } finally {
    loading.value = false
  }
}

const goDetail = (id) => router.push(`/novel/${id}`)
const onPage = (p) => {
  pageNum.value = p
  load()
}

watch(authorId, () => {
  pageNum.value = 1
  load()
})

onMounted(load)
</script>

<template>
  <div>
    <div class="author-head">
      <span class="avatar">{{ (authorName || '作').slice(0, 1) }}</span>
      <div class="meta">
        <h2 class="name">{{ authorName || '作者主页' }}</h2>
        <div class="sub">已发布 {{ total }} 部作品</div>
      </div>
    </div>

    <div class="floor-head">
      <h3 class="section-title">TA 的作品</h3>
      <a class="floor-more" @click="router.push('/novel')">去书库找书 →</a>
    </div>

    <div class="book-grid" v-loading="loading">
      <BookRow
        v-for="d in list"
        :key="d.id"
        :novel="d"
        @click="goDetail(d.id)"
      />
    </div>

    <div v-if="!loading && !list.length" class="empty-tip">这位作者暂时没有已发布的作品</div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        background
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="pageNum"
        @current-change="onPage"
      />
    </div>
  </div>
</template>

<style scoped>
.author-head {
  display: flex; align-items: center; gap: 16px;
  padding: 22px 24px; margin-bottom: 26px;
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 12px;
}
.avatar {
  width: 56px; height: 56px; border-radius: 50%; flex-shrink: 0;
  background: var(--ink); color: var(--paper-2);
  display: flex; align-items: center; justify-content: center;
  font-family: var(--serif); font-size: 24px; font-weight: 600;
}
.name { font-family: var(--serif); font-size: 21px; font-weight: 600; color: var(--ink); }
.sub { margin-top: 5px; font-size: 13px; color: var(--muted); }

.floor-head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.section-title { font-family: var(--serif); font-size: 18px; font-weight: 600; }
.floor-more { margin-left: auto; font-size: 13px; color: var(--cinnabar); cursor: pointer; }
.floor-more:hover { text-decoration: underline; }

.pager { display: flex; justify-content: center; margin-top: 24px; }
</style>
