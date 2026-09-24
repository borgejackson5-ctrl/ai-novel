/**
 * SSE 帧解析：前端所有「AI 流式输出」统一入口。
 *
 * <p>独立成文件的原因：该逻辑原有两份实现（`utils/aiStream.js` 一份、
 * `views/Create.vue` 内手写一份），其中手写的一份缺少 `JSON.parse`：
 * 服务端每段内容以 JSON 字符串字面量发出（帧内不能出现真实换行，
 * 见后端 `common/util/SsePayload`），因此「AI 生成简介」写入表单的文字
 * 带有一对引号、段落换行显示为字面量 `\n`，且不抛异常，属静默的内容损坏。
 * 两份实现必然分叉，本次即为「一份正确、一份错误」。
 *
 * <p>抽成纯函数后不依赖 Vue / DOM，可用 node 直接执行用例
 * （`node frontend/src/utils/sseFrames.spec.mjs`），无需启动浏览器。
 */

/**
 * 解一帧，取出其中的内容。
 *
 * @returns {string|undefined} 非数据帧返回 `undefined`（SSE 允许存在注释帧、心跳帧）
 * @throws 帧格式非法时应抛出，不得将原文拼接返回：拼接会生成一段带引号的
 *         异常文字，作者无法定位问题，也无法从症状反推到此处的解析失败
 */
export function decodeFrame(frame) {
  if (!frame.startsWith('data:')) return undefined
  let raw = frame.slice(5)
  if (raw.startsWith(' ')) raw = raw.slice(1)   // SSE 规范：冒号后面允许有一个空格
  raw = raw.replace(/\r$/, '')                  // 有的代理会把行尾换成分 \r\n
  try {
    return JSON.parse(raw)
  } catch (e) {
    throw new Error('生成内容的传输格式有误，请重试')
  }
}

/**
 * 增量解析器：输入任意长度的原始文本，输出其中已完整接收的内容。
 *
 * <p>必要性：SSE 帧以空行分隔，而网络分片可能在一帧中间切断，
 * 因此需缓存未完成的一段，否则会解析到半句 JSON 并抛错。
 *
 * @returns {{feed: (text: string) => string[]}}
 */
export function createFrameDecoder() {
  let buffer = ''
  return {
    feed(text) {
      buffer += text
      const frames = buffer.split('\n\n')
      buffer = frames.pop()      // 最后一段可能没收完，留到下次
      const out = []
      for (const frame of frames) {
        const chunk = decodeFrame(frame)
        if (chunk !== undefined) out.push(chunk)
      }
      return out
    }
  }
}
