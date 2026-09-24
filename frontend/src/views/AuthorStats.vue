<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getNovelStats } from '../api'
import { fmtWan } from '../utils/cover'
import WorkbenchTabs from '../components/WorkbenchTabs.vue'

const route = useRoute()
const novelId = route.params.id

const loading = ref(true)
const stats = ref(null)

const load = async () => {
  loading.value = true
  try {
    stats.value = await getNovelStats(novelId)
  } finally {
    loading.value = false
  }
}

const chapters = computed(() => stats.value?.chapters || [])
const maxReaders = computed(() =>
  chapters.value.reduce((m, c) => Math.max(m, Number(c.readers || 0)), 0)
)
const barPct = (c) => (maxReaders.value ? Math.max((Number(c.readers || 0) / maxReaders.value) * 100, 2) : 0)

const overview = computed(() => {
  const s = stats.value
  if (!s) return []
  return [
    { label: '阅读人数', value: fmtWan(s.readerCount), hint: '按人去重，同一人反复看只算一次' },
    { label: '累计阅读量', value: fmtWan(s.readCount), hint: '含同一人的多次阅读' },
    { label: '收藏数', value: fmtWan(s.collectCount), hint: '加入书架的人数' },
    { label: '追读率', value: s.retentionRate || '样本不足', hint: '最新一章人数 ÷ 第一章人数' }
  ]
})

onMounted(load)
</script>

<template>
  <div class="stats-page" v-loading="loading">
    <WorkbenchTabs :novel-id="novelId" active="stats" />
    <div class="head">
      <h2 class="title">{{ stats?.novelTitle || '作品数据' }}</h2>
      <el-tag v-if="stats?.serialStatusText" :type="stats.serialStatus === 1 ? 'info' : ''" size="small" effect="plain" round>
        {{ stats.serialStatusText }}
      </el-tag>
    </div>

    <div v-if="stats" class="ov-grid">
      <div v-for="o in overview" :key="o.label" class="ov-card">
        <div class="ov-label">{{ o.label }}</div>
        <div class="ov-value">{{ o.value }}</div>
        <div class="ov-hint">{{ o.hint }}</div>
      </div>
    </div>

    <section v-if="stats" class="panel">
      <h3 class="panel-title">章节阅读人数</h3>
      <p class="panel-sub">
        每一章有多少人读过（按人去重）。曲线掉得最陡的地方，通常就是读者放下这本书的位置。
      </p>

      <div v-if="!chapters.length" class="empty">还没有阅读记录</div>

      <div v-else class="bars">
        <div v-for="c in chapters" :key="c.chapterNo" class="bar-row">
          <span class="bar-no">第{{ c.chapterNo }}章</span>
          <span class="bar-track">
            <span class="bar-fill" :style="{ width: barPct(c) + '%' }"></span>
          </span>
          <span class="bar-val">{{ c.readers }}</span>
          <span class="bar-keep">{{ c.keepRate ? '留存 ' + c.keepRate : '' }}</span>
        </div>
      </div>
    </section>

    <section v-if="stats && stats.dropOffs.length" class="panel">
      <h3 class="panel-title">流失最多的章节</h3>
      <p class="panel-sub">这几章相对上一章掉人最多，值得回头看看节奏或情节。</p>
      <div class="drop-list">
        <div v-for="c in stats.dropOffs" :key="c.chapterNo" class="drop-item">
          <span class="drop-no">第 {{ c.chapterNo }} 章</span>
          <span class="drop-title">{{ c.chapterTitle || '' }}</span>
          <span class="drop-lost">-{{ c.lost }} 人</span>
          <span class="drop-keep">{{ c.keepRate ? '留存 ' + c.keepRate : '' }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.stats-page { max-width: 960px; margin: 0 auto; }
.head { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.head .title { font-size: 18px; margin: 0; }
.ov-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
  margin-bottom: 20px;
}
.ov-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 16px 18px;
  background: var(--paper-2);
}
.ov-label { font-size: 12.5px; color: var(--muted); }
.ov-value { font-family: var(--serif); font-size: 26px; font-weight: 600; margin: 6px 0 4px; }
.ov-hint { font-size: 12px; color: var(--muted); line-height: 1.5; }
.panel {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 18px 20px;
  margin-bottom: 18px;
  background: #fff;
}
.panel-title { font-size: 15px; margin: 0 0 6px; }
.panel-sub { font-size: 12.5px; color: var(--muted); margin: 0 0 16px; line-height: 1.6; }
.empty { color: var(--muted); font-size: 13px; padding: 24px 0; text-align: center; }
.bars { display: flex; flex-direction: column; gap: 8px; }
.bar-row { display: flex; align-items: center; gap: 10px; }
.bar-no { width: 66px; flex-shrink: 0; font-size: 12.5px; color: var(--muted); }
.bar-track { flex: 1; height: 14px; background: var(--line-soft); border-radius: 7px; overflow: hidden; }
.bar-fill { display: block; height: 100%; background: var(--cinnabar); opacity: .72; border-radius: 7px; }
.bar-val { width: 48px; text-align: right; font-size: 12.5px; }
.bar-keep { width: 78px; font-size: 12px; color: var(--muted); }
.drop-list { display: flex; flex-direction: column; }
.drop-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--line-soft);
  font-size: 13px;
}
.drop-item:last-child { border-bottom: none; }
.drop-no { width: 84px; flex-shrink: 0; color: var(--muted); }
.drop-title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.drop-lost { color: var(--cinnabar); font-weight: 500; }
.drop-keep { width: 78px; font-size: 12px; color: var(--muted); text-align: right; }
</style>
