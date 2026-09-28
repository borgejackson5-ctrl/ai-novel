/**
 * aiStats 辅助函数的自检：`node src/utils/aiStats.spec.mjs`。
 *
 * 全部断言都用**字符串**做输入 —— 后端把 Long 序列化为字符串，用数字测等于没测。
 */
import { strict as assert } from 'node:assert'
import { countText, hitRateOf, pctText, quotaPct, quotaStatus, toNum } from './aiStats.js'

assert.equal(toNum('3'), 3, '字符串数字要能转回数字')
assert.equal(toNum('0'), 0)
assert.equal(toNum(undefined), 0, '缺字段按 0 处理，不产生 NaN')
assert.equal(toNum('abc'), 0)
assert.equal(toNum(null), 0)

// 字符串求和：若不转换会得到 "03" 这类结果，数字不同且不报错
assert.equal(hitRateOf([{ hit: '3', miss: '1' }]), 0.75)
assert.ok(Math.abs(hitRateOf([{ hit: '1', miss: '1' }, { hit: '9', miss: '0' }]) - 10 / 11) < 1e-9,
  '多缓存时按总次数合并，不是各命中率取平均（(0.5+1)/2 = 0.75 是错的）')
assert.equal(hitRateOf([]), null, '无读取记录时返回 null，由展示层决定占位符')
assert.equal(hitRateOf(undefined), null)

assert.equal(quotaPct({ used: '100', limit: '100' }), 100)
assert.equal(quotaPct({ used: '150', limit: '100' }), 100, '超额截断到 100，进度条不溢出')
assert.equal(quotaPct({ used: '0', limit: '0' }), 0, '上限为 0 时不产生 NaN')

// 字符串比较："9" >= "100" 为真，直接比会误报告警
assert.equal(quotaStatus({ used: '100', limit: '100' }), 'exception')
assert.equal(quotaStatus({ used: '9', limit: '100' }), undefined)
assert.equal(quotaStatus({ used: '0', limit: '0' }), undefined, '配额关闭时不算用尽')

assert.equal(pctText(0.8), '80.0%')
assert.equal(pctText(0), '0.0%')
assert.equal(pctText(null), '—', '无数据用破折号，0% 会被读作「一次都没命中」')
assert.equal(pctText(undefined), '—')

// token 用量是六位以上的常见来源：不转数字会被当作文本处理，既不分组也不能比较
assert.equal(countText('12345'), (12345).toLocaleString('zh-CN'))
assert.equal(countText(undefined), (0).toLocaleString('zh-CN'), '缺字段显示 0 而不是 NaN')
assert.equal(countText('abc'), (0).toLocaleString('zh-CN'))

console.log('aiStats 辅助函数：全部通过')
