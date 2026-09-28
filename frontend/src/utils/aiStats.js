/**
 * AI 用量页的取数与展示辅助。
 *
 * 这些函数单独放在这里而不是写在页面里，是因为它们要处理一个不会报错的陷阱：
 * 后端把 Long 序列化为字符串（防止 JS 精度丢失），而字符串参与算术不会抛异常，
 * 只会得到一个数字不同的结果 —— 例如求和时 `0 + "3"` 得到字符串 `"03"`，
 * 数字比较时 `"9" >= "100"` 为真。这类错误在页面上只表现为「数字不对」，需要测试拦住。
 */

/** 数值字段的统一入口：后端下发的计数与耗时为字符串 */
export const toNum = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

/**
 * 缓存命中率。
 *
 * 按总读取次数合并计算，而不是对各缓存的命中率取平均：后者会让读取量很小的缓存
 * 与主要缓存等权。无读取记录时返回 null，由展示层决定用什么占位符。
 */
export const hitRateOf = (list) => {
  const rows = list || []
  const hit = rows.reduce((sum, item) => sum + toNum(item?.hit), 0)
  const reads = rows.reduce((sum, item) => sum + toNum(item?.hit) + toNum(item?.miss), 0)
  return reads === 0 ? null : hit / reads
}

/** 额度使用百分比，供进度条使用；上限为 0（该配额已关闭）时按 0 处理，不产生 NaN */
export const quotaPct = (quota) => {
  const limit = toNum(quota?.limit)
  if (!limit) return 0
  return Math.min(100, Math.round((toNum(quota?.used) / limit) * 100))
}

/** 额度用尽时进度条转为告警色 */
export const quotaStatus = (quota) => {
  const limit = toNum(quota?.limit)
  return limit && toNum(quota?.used) >= limit ? 'exception' : undefined
}

/** 比率转百分比文本；无数据时用破折号，避免显示成 0% 而被读作「一次都没命中」 */
export const pctText = (ratio) => (
  ratio === null || ratio === undefined ? '—' : `${(ratio * 100).toFixed(1)}%`
)

/**
 * 计数文本：先转数字再加千分位。
 *
 * 计数不可直接渲染：后端把 Long 序列化为字符串，位数多时既无分隔也不便核对
 * （详见文件头的说明）。token 用量是六位以上的常见来源。
 */
export const countText = (v) => toNum(v).toLocaleString('zh-CN')
