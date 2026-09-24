<script setup>
import { useRouter } from 'vue-router'

// 作品工作台：章节管理 / 全书审查 / 作品信息 / 作品数据共用的一排 tab。
// 目的：一部作品的作者操作只有一个入口，任意一页都能直接切换到另外几页，
// 无需退回「我的作品」列表再进入。
defineProps({
  novelId: { type: [String, Number], required: true },
  active: { type: String, required: true } // manage | review | edit | stats
})

const router = useRouter()
const TABS = [
  { key: 'manage', label: '章节管理', to: (id) => `/novel/${id}/manage` },
  { key: 'review', label: '全书审查', to: (id) => `/novel/${id}/review` },
  { key: 'edit', label: '作品信息', to: (id) => `/novel/${id}/edit` },
  { key: 'stats', label: '作品数据', to: (id) => `/novel/${id}/stats` }
]

const go = (tab, novelId, active) => {
  if (tab.key !== active) router.push(tab.to(novelId))
}
</script>

<template>
  <div class="workbench">
    <nav class="wb-tabs">
      <span
        v-for="t in TABS"
        :key="t.key"
        class="wb-tab"
        :class="{ active: t.key === active }"
        @click="go(t, novelId, active)"
      >{{ t.label }}</span>
    </nav>
    <div class="wb-links">
      <a @click="router.push('/my-works')">全部作品</a>
      <a @click="router.push(`/novel/${novelId}`)">作品页</a>
    </div>
  </div>
</template>

<style scoped>
.workbench {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}
.wb-tabs { display: flex; align-items: center; gap: 6px; }
.wb-tab {
  font-size: 14px;
  color: var(--muted);
  padding: 6px 14px;
  border-radius: 8px;
  cursor: pointer;
  transition: all .18s;
}
.wb-tab:hover { color: var(--cinnabar); background: var(--cinnabar-bg); }
.wb-tab.active {
  font-family: var(--serif);
  font-weight: 600;
  color: var(--ink);
  background: var(--paper-2);
  border: 1px solid var(--line);
}
.wb-links { margin-left: auto; display: flex; align-items: center; gap: 14px; font-size: 13px; }
.wb-links a { color: var(--cinnabar); cursor: pointer; white-space: nowrap; }
.wb-links a:hover { text-decoration: underline; }

@media (max-width: 900px) {
  .workbench { flex-wrap: wrap; gap: 10px; }
  .wb-links { margin-left: 0; }
}
</style>
