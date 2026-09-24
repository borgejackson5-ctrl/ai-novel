/**
 * 分类专属书封生成器
 *
 * 后端 t_novel.cover_url 全部为空，这里用「分类→深色书封配色」方案兜底：
 * 每本小说的书封由分类主色深色渐变 + 标题首字大字构成（纯 CSS，无外网资源）。
 * 后续若 coverUrl 有值，可直接替换 <Poster> 渲染逻辑。
 */

export const DEFAULT_CAT = {
  id: 0,
  name: '小说',
  // 深色底双渐变色（墨色）
  from: '#2b2733',
  to: '#14121a',
  // 强调色（chip）
  accent: '#c4553f'
}

/**
 * 分类 id → 书封配色（与 t_category 中的 7 个基础分类一一对应）
 *
 * 此处为手工调整的主色，修改分类名时需同步 name。
 * 管理端新建的分类不经由此处（其 id 为雪花号），见下方 FALLBACK_PALETTES。
 */
export const CATEGORY_PALETTES = {
  1: { name: '古典名著', from: '#6b4a12', to: '#2a1c05', accent: '#ffd98a' },
  2: { name: '仙侠修真', from: '#243a63', to: '#0e1730', accent: '#9fc0f5' },
  3: { name: '侠义公案', from: '#0e5a50', to: '#05231f', accent: '#ffd76e' },
  4: { name: '历史演义', from: '#2b4a6f', to: '#101c2e', accent: '#8ab6ff' },
  5: { name: '志怪神魔', from: '#2c3e50', to: '#0d141b', accent: '#7ee0c0' },
  6: { name: '世情讽喻', from: '#8e2a63', to: '#3a1140', accent: '#ff8fb8' },
  7: { name: '儿女英雄', from: '#7a3320', to: '#2b0f07', accent: '#ffb08a' }
}

/**
 * 备用配色：管理端新建的分类，其 id 为雪花号（非 1–7 的连续小整数），不会命中上表。
 * 此处按 id 稳定散列取值，同一分类每次得到相同颜色，而非如同 DEFAULT_CAT
 * 一律使用灰色：灰色会使新分类的书看起来像「数据未加载完成」。
 */
const FALLBACK_PALETTES = [
  { from: '#0d5c63', to: '#042a2e', accent: '#7fd8e0' },
  { from: '#4a2f6e', to: '#180e26', accent: '#b9a0f0' },
  { from: '#5c5326', to: '#201c0a', accent: '#e0d07f' },
  { from: '#6e2f52', to: '#280f1d', accent: '#f0a0c0' },
  { from: '#33383d', to: '#111417', accent: '#b0bfc9' }
]

/** 按 id 稳定散列到备用配色（非简单取模：雪花号低位规律性强，取模会导致分布集中） */
function fallbackPalette(id) {
  if (!Number.isFinite(id) || id <= 0) return null
  const s = String(id)
  let h = 0
  for (let i = 0; i < s.length; i += 1) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return FALLBACK_PALETTES[h % FALLBACK_PALETTES.length]
}

/** 按分类取配色：固定 7 类 → 备用色 → 默认灰 */
export function getPalette(categoryId) {
  const id = Number(categoryId)
  return CATEGORY_PALETTES[id] || fallbackPalette(id) || DEFAULT_CAT
}

/** 生成为 <div> 注入的 CSS 变量（供 .poster 使用） */
export function posterVars(novel) {
  const p = getPalette(novel.categoryId)
  return {
    '--pf': p.from,
    '--pt': p.to,
    '--pa': p.accent
  }
}

/** 标题首字符（书封大主视觉，空标题回退「书」） */
export function posterChar(title) {
  if (!title) return '书'
  return [...title.trim()][0] || '书'
}

/** 格式化阅读量：w = 万 */
export function fmtWan(n) {
  if (n == null) return '0'
  return n >= 10000 ? (n / 10000).toFixed(1) + 'w' : String(n)
}
