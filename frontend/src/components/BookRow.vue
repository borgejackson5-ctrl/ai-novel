<script setup>
import { computed } from 'vue'
import { posterVars, posterChar, fmtWan } from '../utils/cover'

const props = defineProps({
  novel: { type: Object, required: true },
  // 榜单名次：>0 时在封面左侧显示编号
  rank: { type: Number, default: 0 },
  // 是否展示一句话简介（紧凑列表可关掉）
  showIntro: { type: Boolean, default: true },
  // 状态文案（如「连载中」「已完结」）
  statusText: { type: String, default: '' },
  // 「我的作品」角标：在「我的作品」列表中全部为本人作品，可关闭以避免视觉冗余
  showMine: { type: Boolean, default: true },
  // 搜索高亮后的标题 HTML（由父组件消毒后传入）
  titleHtml: { type: String, default: '' }
})

defineEmits(['click'])

const chapterText = computed(() =>
  props.novel.totalChapters ? `${props.novel.totalChapters} 章` : ''
)
const wordText = computed(() => {
  const w = Number(props.novel.wordCount || 0)
  if (!w) return ''
  return w >= 10000 ? `${(w / 10000).toFixed(1)} 万字` : `${w} 字`
})
const readText = computed(() =>
  props.novel.readCount != null ? `阅读 ${fmtWan(props.novel.readCount)}` : ''
)
</script>

<template>
  <div class="book-row" @click="$emit('click', novel)">
    <span v-if="rank" class="rank" :class="{ top: rank <= 3 }">{{ rank }}</span>

    <div class="cover" :style="posterVars(novel)">
      <img v-if="novel.coverUrl" :src="novel.coverUrl" alt="" />
      <span v-else class="cover-char">{{ posterChar(novel.title) }}</span>
    </div>

    <div class="info">
      <div class="title-row">
        <span v-if="titleHtml" class="title" v-html="titleHtml"></span>
        <span v-else class="title">{{ novel.title }}</span>
        <span v-if="showMine && novel.isMine" class="mine">我的作品</span>
        <span v-if="statusText" class="status">{{ statusText }}</span>
      </div>

      <p v-if="showIntro && novel.intro" class="intro">{{ novel.intro }}</p>

      <div class="meta">
        <span v-if="novel.categoryName" class="cat">{{ novel.categoryName }}</span>
        <span v-if="novel.author" class="author">{{ novel.author }}</span>
        <span v-if="chapterText">{{ chapterText }}</span>
        <span v-if="wordText">{{ wordText }}</span>
        <span v-if="readText">{{ readText }}</span>
        <span v-if="$slots.action" class="action" @click.stop><slot name="action" /></span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.book-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--paper-2);
  cursor: pointer;
  transition: border-color .18s, background .18s;
}
.book-row:hover { border-color: var(--cinnabar); background: #fff; }

.rank {
  flex-shrink: 0;
  width: 22px;
  padding-top: 4px;
  font-family: var(--serif);
  font-size: 17px;
  color: var(--muted);
  text-align: center;
}
.rank.top { color: var(--cinnabar); font-weight: 600; }

.cover {
  position: relative;
  width: 66px;
  height: 88px;
  border-radius: 5px;
  overflow: hidden;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255, 255, 255, 0.92);
  background: linear-gradient(160deg, var(--pf, #2b2733) 0%, var(--pt, #14121a) 100%);
}
.cover img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.cover-char { font-family: var(--serif); font-size: 26px; font-weight: 600; }

.info { flex: 1; min-width: 0; }
.title-row { display: flex; align-items: center; gap: 8px; }
.title {
  font-size: 15px;
  font-weight: 500;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 搜索命中的高亮片段由 v-html 注入，无法获得 scoped 属性，需使用 :deep 才生效；
   否则 <em> 将按浏览器默认样式渲染为斜体，中文斜体不美观且与全站强调色不一致 */
.title :deep(em) {
  color: var(--cinnabar);
  font-style: normal;
  font-weight: 600;
}
.status {
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--cinnabar);
  background: var(--cinnabar-bg);
  padding: 1px 7px;
  border-radius: 4px;
}
/* 「我的作品」角标：便于作者在书库/榜单中识别本人的作品 */
.mine {
  flex-shrink: 0;
  font-size: 11.5px;
  color: var(--ink);
  border: 1px solid var(--line);
  padding: 0 6px;
  border-radius: 4px;
}
.intro {
  margin-top: 6px;
  font-size: 12.5px;
  color: var(--muted);
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.meta {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--muted);
}
.action { margin-left: auto; }
.cat {
  color: var(--cinnabar);
  background: var(--cinnabar-bg);
  padding: 1px 7px;
  border-radius: 4px;
}
</style>
