<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getBookshelf, removeBookshelf } from '../api'
import BookRow from '../components/BookRow.vue'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const keyword = ref('')
const pageNum = ref(1)
const pageSize = 12
const total = ref(0)

// 书架接口返回的是另一套字段名，统一成 BookRow 的形状
const normalize = (s) => ({
  id: s.novelId,
  title: s.novelTitle,
  coverUrl: s.coverUrl,
  categoryName: s.categoryName,
  author: s.author,
  totalChapters: s.totalChapters,
  readCount: s.readCount,
  // 失效状态：deleted=作品已被删除（点进去必然 404），offline=已下架（已解锁章节仍可读）
  offline: !!s.offline,
  deleted: !!s.deleted
})

const load = async (page = 1) => {
  pageNum.value = page
  loading.value = true
  try {
    const res = await getBookshelf({
      pageNum: page,
      pageSize,
      keyword: keyword.value.trim() || undefined
    })
    list.value = (res?.list || []).map(normalize)
    total.value = Number(res?.total || 0)
  } catch (e) { /* 已在拦截器提示 */ } finally {
    loading.value = false
  }
}

const doSearch = () => load(1)
const clearSearch = () => {
  keyword.value = ''
  load(1)
}

const openNovel = (n) => router.push(`/novel/${n.id}`)

const remove = async (n) => {
  try {
    await ElMessageBox.confirm(`将《${n.title}》移出书架？`, '移出书架', {
      type: 'warning',
      confirmButtonText: '移出',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    await removeBookshelf(n.id)
    ElMessage.success('已移出书架')
    // 移出后整页重取：本页最后一条被移走时回退一页，避免停在空页
    const target = list.value.length === 1 && pageNum.value > 1 ? pageNum.value - 1 : pageNum.value
    await load(target)
  } catch (e) { /* 已在拦截器提示 */ }
}

onMounted(() => load(1))
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="section-title">我的书架</h2>
      <span class="count">共 {{ total }} 部</span>
      <div class="head-right">
        <el-input
          v-model="keyword"
          class="shelf-search"
          size="small"
          placeholder="搜索书名"
          clearable
          @keyup.enter="doSearch"
          @clear="doSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <a class="more" @click="router.push('/novel')">去书库找书 →</a>
      </div>
    </div>

    <div class="book-grid" v-loading="loading">
      <template v-for="d in list" :key="d.id">
        <div
          v-if="d.deleted || d.offline"
          class="shelf-stale"
          :class="{ clickable: !d.deleted }"
          @click="!d.deleted && openNovel(d)"
        >
          <div class="stale-cover"><el-icon><Document /></el-icon></div>
          <div class="stale-body">
            <div class="stale-title">{{ d.title }}</div>
            <div class="stale-meta">
              <span class="stale-tag">{{ d.deleted ? '已删除' : '已下架' }}</span>
              <span class="stale-hint">{{ d.deleted ? '作品已被删除' : '已解锁的章节仍可阅读' }}</span>
            </div>
          </div>
          <span class="rm" @click.stop="remove(d)">移出</span>
        </div>

        <BookRow v-else :novel="d" @click="openNovel(d)">
          <template #action>
            <span class="rm" @click="remove(d)">移出</span>
          </template>
        </BookRow>
      </template>
    </div>

    <el-pagination
      v-if="total > pageSize"
      class="shelf-pager"
      layout="prev, pager, next"
      :total="total"
      :page-size="pageSize"
      :current-page="pageNum"
      @current-change="load"
    />

    <div v-if="!loading && !list.length" class="empty">
      <template v-if="keyword.trim()">
        <div class="empty-t">没有找到书名含「{{ keyword.trim() }}」的收藏</div>
        <div class="empty-s">换个关键词，或清空搜索看看全部收藏</div>
        <el-button @click="clearSearch">清空搜索</el-button>
      </template>
      <template v-else>
        <div class="empty-t">书架还是空的</div>
        <div class="empty-s">在作品详情页点「加入书架」，就能在这里接着看</div>
        <el-button type="primary" @click="router.push('/novel')">去书库</el-button>
      </template>
    </div>
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
.head-right { margin-left: auto; display: flex; align-items: center; gap: 14px; }
.shelf-search { width: 190px; }
.more {
  font-size: 13px;
  color: var(--cinnabar);
  cursor: pointer;
  white-space: nowrap;
}
.more:hover { text-decoration: underline; }
.shelf-pager { margin-top: 22px; justify-content: center; }

.rm { color: var(--muted); cursor: pointer; }
.rm:hover { color: var(--cinnabar); }

/* 失效条目占位：与 BookRow 同尺寸，但明确「不可读」的视觉 */
.shelf-stale {
  display: flex; align-items: center; gap: 14px;
  padding: 12px; border-radius: 10px;
  background: var(--paper-2); border: 1px solid var(--line);
  opacity: .72;
}
.shelf-stale.clickable { cursor: pointer; }
.shelf-stale.clickable:hover { opacity: 1; border-color: var(--cinnabar); }
.stale-cover {
  width: 66px; height: 88px; border-radius: 5px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 22px; color: var(--muted);
  background: var(--bg); border: 1px dashed var(--line);
}
.stale-body { flex: 1; min-width: 0; }
.stale-title { font-size: 14.5px; color: var(--muted); }
.stale-meta { margin-top: 8px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 12px; }
.stale-tag {
  color: var(--muted); border: 1px solid var(--line);
  padding: 1px 7px; border-radius: 4px;
}
.stale-hint { color: var(--muted); }

.empty {
  padding: 64px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
}
.empty-t { font-family: var(--serif); font-size: 17px; color: var(--ink); }
.empty-s { font-size: 13px; color: var(--muted); margin-bottom: 8px; }

/* 窄屏（手机）：页头是一行 flex（标题 + 计数 + 右对齐的搜索框），
   .shelf-search 固定 190px，把「我的书架」压到一字宽、逐字竖排。
   改成允许换行、右区独占一行铺满。 */
@media (max-width: 768px) {
  .page-head { flex-wrap: wrap; }
  .head-right { margin-left: 0; width: 100%; }
  .shelf-search { width: 100%; flex: 1; }
}
</style>
