<script setup>
import { ref, onMounted, watch } from "vue";
import { useRouter, useRoute } from "vue-router";
import { getNovelPage, searchNovel, smartSearchNovel, getCategories } from "../api";
import { sanitizeHighlight } from "../utils/html";
import BookRow from "../components/BookRow.vue";

const router = useRouter();
const route = useRoute();
const list = ref([]);
const total = ref(0);
const categories = ref([]);
const query = ref({
  pageNum: 1,
  pageSize: 12,
  keyword: "",
  categoryId: null,
  serialStatus: null,
  minWords: null,
  maxWords: null,
  sort: "hot",
});

// 字数档位 → 字数区间（后端按 word_count 上下界筛，可单边）
const WORD_RANGES = [
  { label: "10 万字以下", minWords: null, maxWords: 100000 },
  { label: "10 - 50 万字", minWords: 100000, maxWords: 500000 },
  { label: "50 - 100 万字", minWords: 500000, maxWords: 1000000 },
  { label: "100 万字以上", minWords: 1000000, maxWords: null },
];
const wordRange = ref(null);

const onWordRangeChange = () => {
  const picked = WORD_RANGES[wordRange.value];
  query.value.minWords = picked ? picked.minWords : null;
  query.value.maxWords = picked ? picked.maxWords : null;
  search();
};

// 智能搜索：AI 解析自然语言意图 → 结构化 ES 查询，失败自动降级关键词
const smartMode = ref(false);
const loading = ref(false);
const smartInfo = ref(null);
// 翻页时带回上次解析出的意图，后端跳过 LLM 调用
const lastIntent = ref(null);

const load = async () => {
  loading.value = true;
  try {
    if (smartMode.value && query.value.keyword) {
      const payload = {
        query: query.value.keyword,
        page: query.value.pageNum,
        size: query.value.pageSize,
      };
      if (lastIntent.value) {
        Object.assign(payload, lastIntent.value);
      }
      const data = await smartSearchNovel(payload);
      smartInfo.value = data;
      // 缓存解析出的意图供翻页复用；降级结果不缓存
      lastIntent.value = data.degraded
        ? null
        : { keywords: data.keywords, tags: data.tags, categoryId: data.categoryId };
      list.value = data.page.list;
      total.value = data.page.total;
    } else if (query.value.keyword) {
      // 关键词走 ES 全文搜索（title 返回高亮片段），筛选条件一并下推给 ES
      smartInfo.value = null;
      const data = await searchNovel({
        keyword: query.value.keyword,
        page: query.value.pageNum,
        size: query.value.pageSize,
        categoryId: query.value.categoryId,
        serialStatus: query.value.serialStatus,
        minWords: query.value.minWords,
        maxWords: query.value.maxWords,
      });
      list.value = data.list;
      total.value = data.total;
    } else {
      smartInfo.value = null;
      const data = await getNovelPage(query.value);
      list.value = data.list;
      total.value = data.total;
    }
  } finally {
    loading.value = false;
  }
};

const search = () => {
  query.value.pageNum = 1;
  // 新查询重新解析意图
  lastIntent.value = null;
  load();
};

const onSmartToggle = () => {
  smartInfo.value = null;
  lastIntent.value = null;
  if (query.value.keyword) {
    search();
  }
};

const goDetail = (id) => router.push(`/novel/${id}`);

// 降级且零结果时的出口：直接切换到「按分类浏览」。
// 清空关键词是必要条件：空关键词不会进入智能分支，从而不会用同一句话再次搜索。
const pickCategory = (id) => {
  smartMode.value = false;
  smartInfo.value = null;
  lastIntent.value = null;
  query.value.keyword = "";
  query.value.categoryId = id;
  search();
};

// 顶栏搜索框 / 外部链接带过来的关键词
watch(
  () => route.query.keyword,
  (v) => {
    const kw = String(v || "");
    if (kw === query.value.keyword) return;
    query.value.keyword = kw;
    search();
  }
);

onMounted(async () => {
  query.value.keyword = String(route.query.keyword || "");
  categories.value = await getCategories();
  load();
});
</script>

<template>
  <div>
    <div class="page-head">
      <h2 class="section-title">书库</h2>
      <span class="count">共 {{ total }} 部作品</span>
    </div>

    <div class="filter-bar">
      <el-input
        v-model="query.keyword"
        :placeholder="smartMode ? '试着说：想看穿越重生的爽文' : '搜索书名'"
        :style="{ width: smartMode ? '320px' : '240px' }"
        clearable
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button type="primary" :loading="loading" @click="search">搜索</el-button>
      <div class="smart-toggle">
        <el-switch v-model="smartMode" @change="onSmartToggle" />
        <span class="smart-toggle-label" :class="{ on: smartMode }">AI 智能搜索</span>
      </div>
      <span class="spacer"></span>
      <el-radio-group v-model="query.sort" :disabled="smartMode" @change="search">
        <el-radio-button value="hot">最热</el-radio-button>
        <el-radio-button value="latest">最新</el-radio-button>
      </el-radio-group>
    </div>

    <div class="filter-bar filters">
      <el-select
        v-model="query.categoryId"
        :disabled="smartMode"
        placeholder="全部分类"
        clearable
        style="width: 150px"
        @change="search"
      >
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select
        v-model="query.serialStatus"
        :disabled="smartMode"
        placeholder="连载状态"
        clearable
        style="width: 130px"
        @change="search"
      >
        <el-option label="连载中" :value="0" />
        <el-option label="已完结" :value="1" />
      </el-select>
      <el-select
        v-model="wordRange"
        :disabled="smartMode"
        placeholder="字数区间"
        clearable
        style="width: 150px"
        @change="onWordRangeChange"
      >
        <el-option v-for="(r, i) in WORD_RANGES" :key="i" :label="r.label" :value="i" />
      </el-select>
      <span v-if="smartMode" class="filter-note">智能搜索开启时由 AI 统一理解条件</span>
    </div>

    <div v-if="smartInfo && smartInfo.aiUnderstanding" class="smart-bar">
      <el-icon class="smart-icon"><MagicStick /></el-icon>
      <span class="smart-text">AI 理解为：{{ smartInfo.aiUnderstanding }}</span>
    </div>
    <div v-else-if="smartInfo && smartInfo.degraded" class="smart-bar degraded">
      <el-icon class="smart-icon"><Lightning /></el-icon>
      <span class="smart-text">AI 暂不可用，已按关键词为你搜索</span>
    </div>

    <div v-loading="loading" class="book-grid">
      <BookRow
        v-for="d in list"
        :key="d.id"
        :novel="d"
        :title-html="sanitizeHighlight(d.highlightTitle || d.title)"
        :status-text="d.serialStatus === 1 ? d.serialStatusText : ''"
        @click="goDetail(d.id)"
      />
    </div>

    <div v-if="!loading && !list.length" class="empty-tip">
      没有找到匹配的作品，换个关键词或分类试试
      <!-- 降级（AI 不可用）且零结果时后端会返回一组分类入口，
           提供一个可直接点击的出口，优于让用户自行另想关键词 -->
      <div v-if="smartInfo && smartInfo.categories && smartInfo.categories.length" class="cat-suggest">
        <span class="cat-hint">也可以直接看看这些分类：</span>
        <el-tag
          v-for="c in smartInfo.categories"
          :key="c.id"
          class="cat-chip"
          @click="pickCategory(c.id)"
        >{{ c.name }}</el-tag>
      </div>
    </div>

    <div class="pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNum"
        @current-change="(p) => { query.pageNum = p; load(); }"
      />
    </div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}
.page-head .section-title { margin-bottom: 0; }
.count { color: var(--muted); font-size: 13px; }

.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.filter-bar.filters { margin-top: -8px; }
.spacer { flex: 1; }
.filter-note { font-size: 12.5px; color: var(--muted); }
.smart-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
}
.smart-toggle-label {
  font-size: 13px;
  color: var(--muted);
  cursor: pointer;
  user-select: none;
  transition: color 0.2s;
}
.smart-toggle-label.on { color: var(--primary); font-weight: 500; }

.smart-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
  padding: 10px 14px;
  border-radius: 8px;
  background: var(--cinnabar-bg);
  border: 1px solid rgba(178, 58, 46, 0.25);
  font-size: 13px;
  color: var(--ink-2);
}
.smart-bar.degraded {
  background: #fdf6ec;
  border-color: #f3d19e;
  color: #a06a1a;
}
.smart-icon { font-size: 14px; }

.pager { display: flex; justify-content: center; margin-top: 26px; }

/* 降级时的分类出口：换行 + 居中，窄屏也不会挤成一行 */
.cat-suggest {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.cat-hint { font-size: 13px; color: var(--muted); }
.cat-chip { cursor: pointer; }
</style>
