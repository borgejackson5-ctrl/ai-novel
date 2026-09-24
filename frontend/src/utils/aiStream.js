// 这条顶层 import 是安全的：sseFrames 只做纯字符串解析，不牵连下面那条环
import { createFrameDecoder } from './sseFrames'
// 失败分层单独一个文件：生成简介那条链路（Create.vue）也用它，两边共用一份
import {
  AiStreamError,
  FailureKind,
  fromFetchError,
  fromHttpStatus,
  fromStreamBreak,
  isAbort
} from './streamError'

// 但 store 不可顶层 import：store/user.js 依赖 api/index.js，而 api/index.js 依赖本文件，
// 顶层相互 import 会形成循环依赖。故在函数内部动态导入，与 request.js 处理同一循环依赖的方式一致
// （token 的读取发生在调用时，此时各模块均已就绪）
const tokenOf = async () => {
  const { useUserStore } = await import('../store/user')
  return useUserStore().token
}

/**
 * AI 写作的流式请求（续写 / 润色）。
 *
 * 不使用 axios：浏览器端 axios 无法获取增量响应体，`responseType: 'stream'` 为 Node 独有能力。
 * 因此使用 fetch + ReadableStream，与项目内「AI 简介流式生成」的实现方式一致。
 *
 * 每段内容编码为 JSON 字符串的原因：SSE 帧以空行分隔，帧内 `data:` 之后不得出现真实换行。
 * 续写输出天然包含段落换行，直接写入会将一帧拆为两帧，后半段被当作未知字段丢弃，
 * 表现为「生成文字缺失若干换行」，不抛异常，属静默的排版损坏。编码为 JSON 后
 * 换行变为 `\n` 两个字符，帧始终保持单行。
 *
 * @param url     接口路径（不含 /api 前缀）
 * @param body    请求体
 * @param options.onChunk 每收到一段增量文本：(chunk, 累计全文) => void
 * @param options.onUnits 收到「本次扣了多少字」时回调（服务端放在响应头里下发）
 * @param options.signal  AbortSignal，用于「停止」
 * @returns 生成的全文
 */
export async function aiStream(url, body, { onChunk, onUnits, signal } = {}) {
  const token = await tokenOf()

  // fetch 自身抛错表示尚未连接（断网 / 服务未启动 / 跨域被拦截）。
  // 用户主动停止也走此分支，但不属于失败，原样抛出由调用方按「已停止」处理
  let res
  try {
    res = await fetch(`/api${url}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: token } : {})
      },
      body: JSON.stringify(body),
      signal
    })
  } catch (e) {
    if (isAbort(e)) throw e
    // 若原样抛出，用户看到的是浏览器返回的「Failed to fetch」（英文）
    throw fromFetchError(e)
  }

  // 开流之前的失败（额度不足 / 入参不合法 / 限流 / AI 不可用）返回普通 JSON，非事件流。
  // 此处需解析出可读提示，否则作者只能看到「生成失败」，
  // 无法区分是额度不足还是选中文字过长
  if (!res.ok) {
    let payload = null
    try {
      payload = await res.json()
    } catch (_) {
      // 响应体不是 JSON（网关错误页等），交由分层函数兜底
    }
    throw fromHttpStatus(res.status, payload)
  }

  const charged = res.headers.get('X-AI-Charged-Units')
  if (charged && onUnits) {
    onUnits(Number(charged))
  }

  if (!res.body) {
    // SSE 正常必带 body；此分支表示服务端未按事件流返回，避免后续对 null 取值报错
    throw new AiStreamError(FailureKind.SERVER, '服务器没有返回内容，请稍后重试', { status: res.status })
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  // 帧解析统一在 utils/sseFrames.js，全项目唯一实现。
  // 此前与 Create.vue 内的手写实现各存一份，后者缺少 JSON.parse，生成简介时多出一对引号。
  const frames = createFrameDecoder()
  let acc = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      for (const chunk of frames.feed(decoder.decode(value, { stream: true }))) {
        acc += chunk
        if (onChunk) onChunk(chunk, acc)
      }
    }
  } catch (e) {
    // 流已建立（HTTP 200），失败仅在此层可见。分两种：
    //   · 用户主动停止 → 非错误，原样抛出；
    //   · 其他（连接中断、服务端中途退出）→ 按「中断」处理，并携带已接收字符数，
    //     调用方据此提示「已生成的部分可以保留」，否则用户会认为内容全部丢失并重试（再次扣减额度）
    if (isAbort(e)) throw e
    throw fromStreamBreak(acc.length)
  }

  return acc
}
