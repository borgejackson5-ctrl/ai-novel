/**
 * HTML 安全工具。
 */

/**
 * 把 ES 高亮片段安全化：只保留 <em> 高亮标签，转义其余 HTML。
 *
 * <p>书名/简介是用户可写字段，ES 高亮返回的片段（highlightTitle）可能夹带用户构造的
 * HTML（如 <img onerror=...>）。若直接 v-html 渲染会触发存储型 XSS。
 * 因此先把所有 HTML 特殊字符转义，再仅还原受信任的 <em>/</em> 高亮标签。
 *
 * @param {string} html 高亮片段或纯文本
 * @returns {string} 可安全用于 v-html 的字符串
 */
export function sanitizeHighlight(html) {
  if (!html) return ''
  return String(html)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/&lt;em&gt;/g, '<em>')
    .replace(/&lt;\/em&gt;/g, '</em>')
}
