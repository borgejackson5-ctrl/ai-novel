// 前端「流式失败分层」的判据（纯逻辑，不启动服务、不使用浏览器）
//
// 跑法： node scripts/verify-stream-error.mjs
//       （Windows 上若 node 不在 PATH：用 <项目自带或系统安装的> node.exe 直接跑本文件）
//
// 单独一个脚本的原因：后端有 mvn test 覆盖，前端没有测试框架，
// 而本次改动内容恰是「失败时给用户展示哪一条文案」；此类问题出错不会报错，
// 只会让用户看到「Failed to fetch」这类英文原文，或一句「生成失败」。
// 分层逻辑为纯函数，用 node 直接执行即可验证，无需为此引入一整套测试框架。
import {
  AiStreamError,
  FailureKind,
  fromHttpStatus,
  fromFetchError,
  fromStreamBreak,
  isAbort
} from '../frontend/src/utils/streamError.js'

let pass = 0
let fail = 0

function check(name, cond, extra) {
  if (cond) {
    pass++
    console.log('  ok   ' + name)
  } else {
    fail++
    console.log('  FAIL ' + name + (extra ? '   -> ' + extra : ''))
  }
}

// 用户可见文案中不得出现技术词，这是本项目对界面文字的强制要求
const TECH_WORDS = ['fetch', 'HTTP', 'http', 'undefined', 'null', '[object', 'Error:', 'status']
function containsTechWord(text) {
  return TECH_WORDS.find((w) => String(text).includes(w)) || ''
}

console.log('== 开流前的失败分类 ==')
{
  const e = fromHttpStatus(500, null)
  check('500 -> server', e.kind === FailureKind.SERVER, e.kind)
  check('500 的文案不提技术细节', !containsTechWord(e.message), e.message)
  check('500 的文案带状态码，便于反馈', e.message.includes('500'), e.message)
}
{
  const e = fromHttpStatus(503, { code: null, msg: 'Service Unavailable' })
  check('5xx 优先按服务端错处理（即使 body 里有 msg）', e.kind === FailureKind.SERVER, e.kind)
}
{
  const msg = '今天的免费字数已经用完了，明天再来试试'
  const e = fromHttpStatus(400, { code: 10024, msg })
  check('400 -> business', e.kind === FailureKind.BUSINESS, e.kind)
  check('业务错原样用后端的话', e.message === msg, e.message)
  check('业务错带上后端错误码', e.code === 10024, String(e.code))
}
{
  const e = fromHttpStatus(429, { code: 10004, msg: '操作过于频繁，请 60 秒后再试' })
  check('429（限流）归业务错，文案来自后端', e.kind === FailureKind.BUSINESS && e.message.includes('60 秒'), e.message)
}
{
  const e = fromHttpStatus(401, { code: 401, msg: '未登录或登录已失效' })
  check('401 归业务错，用后端文案', e.kind === FailureKind.BUSINESS && e.message === '未登录或登录已失效', e.message)
}
{
  const e = fromHttpStatus(400, null)
  check('4xx 但响应体不是 JSON -> 仍按业务错，中性文案', e.kind === FailureKind.BUSINESS, e.kind)
  check('4xx 无 body 的文案不提技术词', !containsTechWord(e.message), e.message)
}
{
  const e = fromHttpStatus(403, { msg: '' })
  check('body 里 msg 是空串时不吞掉，退回中性文案', e.kind === FailureKind.BUSINESS && e.message.length > 0, e.message)
}

console.log('== 连不上（fetch 自己抛错）==')
{
  // 实际情况为 TypeError: fetch failed，断言据此编写
  const e = fromFetchError(new TypeError('fetch failed'))
  check('-> network', e.kind === FailureKind.NETWORK, e.kind)
  check('不把浏览器的英文原话透给用户', !e.message.includes('fetch failed'), e.message)
  check('network 文案是中文且提到网络', e.message.includes('网络'), e.message)
}

console.log('== 流中途断掉（HTTP 已经是 200）==')
{
  const withContent = fromStreamBreak(120)
  check('-> interrupted', withContent.kind === FailureKind.INTERRUPTED, withContent.kind)
  check('已收到内容时，文案说明「已生成的部分可以保留」', withContent.message.includes('保留'), withContent.message)
  check('interrupted 的 status 记 200（它确实是 200）', withContent.status === 200, String(withContent.status))

  const noContent = fromStreamBreak(0)
  check('什么都没收到时，不硬说「可以保留」', !noContent.message.includes('保留'), noContent.message)
}

console.log('== 用户主动停止不算失败 ==')
check('isAbort 认得 AbortError', isAbort(Object.assign(new Error('aborted'), { name: 'AbortError' })) === true)
check('普通错误不是 abort', isAbort(new Error('boom')) === false)
check('null 不是 abort', isAbort(null) === false)

console.log('== 所有文案都该是「人话」==')
const all = [
  fromHttpStatus(500, null),
  fromHttpStatus(400, { msg: '今天的免费字数已经用完了，明天再来试试' }),
  fromHttpStatus(400, null),
  fromFetchError(new TypeError('fetch failed')),
  fromStreamBreak(10),
  fromStreamBreak(0)
]
for (const e of all) {
  const bad = containsTechWord(e.message)
  check('文案无技术词：' + e.message.slice(0, 18) + '…', !bad, bad ? '包含 "' + bad + '"' : '')
}
check('错误的 name 统一为 AiStreamError（便于排查）', all.every((e) => e.name === 'AiStreamError'))
check('错误都能被 instanceof Error 捕获', all.every((e) => e instanceof Error))

console.log('\n结果：' + pass + ' 通过 / ' + fail + ' 失败')
process.exit(fail === 0 ? 0 : 1)
