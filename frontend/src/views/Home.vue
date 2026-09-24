<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getHotRank, getCategories, getNovelPage, probeNovel } from '../api'
import { getBookmark } from '../utils/reading'
import { pullProgress } from '../utils/readerSync'
import BookRow from '../components/BookRow.vue'

const router = useRouter()
// 挂载后先拉云端书签再回填；null 表示无续读记录（含跨设备）
const bookmark = ref(null)
// 书签指向的作品是否已下架（入口文案据此标注）
const bookmarkOffline = ref(false)
const hotList = ref([])
const categories = ref([])
const novelList = ref([])
const total = ref(0)
const loading = ref(false)
const selectedCat = ref(0)

// 首页榜单只铺 Top10，完整名次去 /rank
const hotTop = computed(() => hotList.value.slice(0, 10))

const loadRank = async () => {
  try {
    hotList.value = await getHotRank()
  } catch (e) { /* 限流提示已在拦截器 */ }
}

const loadCategories = async () => {
  try {
    categories.value = await getCategories()
  } catch (e) { /* 忽略 */ }
}

const loadNovel = async () => {
  loading.value = true
  try {
    // 分类筛选走后端：前端只拉当前分类，避免「先拉 50 本再本地过滤」的假分页
    // 首页按最新上架排序：新导入的书可立即出现在首页，否则会一直排在阅读量排序的末尾
    const params = { pageNum: 1, pageSize: 12, sort: 'latest' }
    if (selectedCat.value) params.categoryId = selectedCat.value
    const data = await getNovelPage(params)
    novelList.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

const selectCat = (id) => {
  selectedCat.value = id
  loadNovel()
}

const goDetail = (id) => router.push(`/novel/${id}`)
const goContinue = () => {
  if (bookmark.value) router.push(`/novel/${bookmark.value.novelId}/chapter/${bookmark.value.chapterId}`)
}

/**
 * 回填「继续阅读」栏。书签为本地数据，无法感知后端变化（作品被删、被驳回、被下架），
 * 直接跳转会进入无法打开的阅读器。此处静默探测一次：获取不到则收起入口。
 */
const resolveBookmark = async () => {
  const bm = getBookmark()
  if (!bm) {
    bookmark.value = null
    return
  }
  try {
    const detail = await probeNovel(bm.novelId)
    if (!detail) {
      bookmark.value = null
      return
    }
    bookmark.value = bm
    // 已下架的书仍可续读（已解锁章节不收回），但入口上要标注清楚
    bookmarkOffline.value = !!detail.offline
  } catch (e) {
    // 已删除 / 未过审：本次先收起，不清理本地书签，作品恢复正常后入口可自动恢复
    bookmark.value = null
  }
}

onMounted(async () => {
  loadRank()
  loadCategories()
  loadNovel()
  // 云同步：有云端书签则覆盖本地镜像，再回填到「继续阅读」栏
  await pullProgress()
  await resolveBookmark()
})
</script>

<template>
  <div class="home">
    <section v-if="bookmark" class="continue-bar" @click="goContinue">
      <el-icon class="cb-icon"><Reading /></el-icon>
      <div class="cb-info">
        <div class="cb-title">
          继续阅读 · {{ bookmark.novelTitle }}
          <span v-if="bookmarkOffline" class="cb-badge">已下架</span>
        </div>
        <div class="cb-chapter">上次读到：第 {{ bookmark.chapterNo }} 章 {{ bookmark.chapterTitle }}</div>
      </div>
      <span class="cb-btn">继续 →</span>
    </section>

    <section class="floor" v-if="hotList.length">
      <div class="floor-head">
        <h2 class="section-title">热门榜单</h2>
        <span class="floor-note">按实时阅读热度排序</span>
        <a class="floor-more" @click="router.push('/rank')">完整榜单 →</a>
      </div>
      <div class="book-grid">
        <BookRow
          v-for="(d, i) in hotTop"
          :key="d.id"
          :novel="d"
          :rank="i + 1"
          @click="goDetail(d.id)"
        />
      </div>
    </section>

    <section class="floor">
      <div class="floor-head">
        <h2 class="section-title">分类精选</h2>
        <div class="cat-chips">
          <span class="cat-chip" :class="{ active: selectedCat === 0 }" @click="selectCat(0)">全部</span>
          <span
            v-for="c in categories"
            :key="c.id"
            class="cat-chip"
            :class="{ active: selectedCat === c.id }"
            @click="selectCat(c.id)"
          >{{ c.name }}</span>
        </div>
        <a class="floor-more" @click="router.push('/novel')">全部作品 →</a>
      </div>

      <div class="book-grid" v-if="loading">
        <div v-for="i in 6" :key="'sk' + i" class="sk-row">
          <div class="sk-cover el-skeleton__item el-skeleton__image"></div>
          <div class="sk-lines">
            <div class="sk-line w60"></div>
            <div class="sk-line w90"></div>
            <div class="sk-line w40"></div>
          </div>
        </div>
      </div>

      <div class="book-grid" v-else>
        <BookRow
          v-for="d in novelList"
          :key="d.id"
          :novel="d"
          @click="goDetail(d.id)"
        />
      </div>

      <div v-if="!loading && !novelList.length" class="empty-tip">
        该分类下暂时没有作品，换个分类看看
      </div>
    </section>

    <footer class="site-footer">
      <div class="foot-brand">
        <span class="foot-logo">灵阅</span>
        <span class="foot-slogan">小说阅读与创作平台</span>
      </div>
      <div class="foot-links">
        <a @click="router.push('/')">首页</a>
        <a @click="router.push('/novel')">书库</a>
        <a @click="router.push('/rank')">排行榜</a>
        <a @click="router.push('/shelf')">书架</a>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.home { padding-bottom: 8px; }

.continue-bar {
  display: flex; align-items: center; gap: 16px; cursor: pointer;
  padding: 16px 20px; border-radius: 10px;
  background: var(--paper-2); border: 1px solid var(--line);
  transition: border-color .18s;
}
.continue-bar:hover { border-color: var(--cinnabar); }
.cb-icon { font-size: 22px; color: var(--cinnabar); }
.cb-info { flex: 1; min-width: 0; }
.cb-title { font-size: 15px; font-weight: 500; color: var(--ink); }
.cb-chapter {
  margin-top: 4px; font-size: 13px; color: var(--muted);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.cb-btn { font-size: 14px; color: var(--cinnabar); flex-shrink: 0; }
.cb-badge {
  margin-left: 8px; font-size: 11.5px; color: var(--muted);
  border: 1px solid var(--line); padding: 1px 7px; border-radius: 4px;
}

.cat-chips { display: flex; gap: 8px; flex-wrap: wrap; }
.cat-chip {
  cursor: pointer; font-size: 13px; padding: 5px 13px; border-radius: 6px;
  background: var(--paper-2); color: var(--ink-2);
  border: 1px solid var(--line);
  transition: all .18s;
}
.cat-chip:hover { border-color: var(--cinnabar); color: var(--cinnabar); }
.cat-chip.active { background: var(--cinnabar); color: #fff; border-color: var(--cinnabar); }

.sk-row {
  display: flex; gap: 14px; padding: 12px;
  border: 1px solid var(--line); border-radius: 10px;
  background: var(--paper-2);
}
.sk-cover { width: 66px; height: 88px; border-radius: 5px; flex-shrink: 0; }
.sk-lines { flex: 1; min-width: 0; padding-top: 6px; }
.sk-line { height: 10px; border-radius: 3px; margin-bottom: 10px; }
.sk-line.w60 { width: 60%; }
.sk-line.w90 { width: 90%; }
.sk-line.w40 { width: 40%; }

.site-footer {
  margin-top: 46px; padding: 26px 6px 10px; border-top: 1px solid var(--line);
  display: flex; align-items: center; justify-content: space-between;
  flex-wrap: wrap; gap: 12px;
  color: var(--muted); font-size: 13px;
}
.foot-brand { display: flex; align-items: center; gap: 10px; }
.foot-logo { font-family: var(--serif); font-size: 16px; font-weight: 600; color: var(--ink); letter-spacing: 1px; }
.foot-links { display: flex; gap: 18px; }
.foot-links a { cursor: pointer; transition: color .18s; }
.foot-links a:hover { color: var(--cinnabar); }
</style>
