/**
 * 时间格式化工具
 *
 * 后端返回 ISO 时间字符串（形如 `YYYY-MM-DDTHH:mm:ss`），统一在此转成前端展示格式，
 * 避免各页面各自编写 `String(t).replace('T',' ').slice(...)` 的碎片化实现。
 */

/**
 * ISO 时间 → 前端展示字符串
 *
 * @param {string} t   后端时间字符串
 * @param {number} len 截取精度：10=仅日期，16=到分钟，19=到秒
 * @returns {string} 格式化后的时间，空值返回空串
 */
export function fmtTime(t, len = 16) {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, len)
}
