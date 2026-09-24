/**
 * 流式请求的失败分层。
 *
 * <p>背景：SSE 失败若只返回「生成失败，请稍后重试」，
 * 实际包含三类互不相同、用户可执行动作也不同的失败：
 *
 * <ul>
 *   <li><b>网络错</b>（连不上、断网）：由用户侧解决，检查网络后重试；</li>
 *   <li><b>服务端错</b>（5xx）：用户无法解决，重试无效；</li>
 *   <li><b>业务错</b>（4xx：额度用完 / 参数不合法 / 太频繁）：后端已返回可读提示，
 *       按提示操作（充值、缩短文本、稍后重试）。</li>
 * </ul>
 *
 * <p>三类错误混在同一个 {@code Error} 中时只能读取 message 推断类型；
 * 网络错时 message 为浏览器返回的 {@code Failed to fetch}（英文），会直接展示给用户。
 *
 * <p>第四类容易遗漏：流已建立后中途中断。此时 HTTP 状态码为 200
 * （开流时即已返回 200），错误发生在 {@code reader.read()} 中，
 * 前端收到的是浏览器抛出的网络异常。与网络错的区别在于用户已获取部分内容，
 * 提示中需说明该部分可保留，否则用户会认为内容全部丢失并重新生成（再次扣减额度）。
 *
 * <p>独立成文件的原因：续写/润色（`utils/aiStream.js`）与生成简介（`Create.vue`）
 * 是两条独立的 fetch 链路，原各自实现「读取 JSON 取 msg」。分层逻辑集中于此，
 * 两条链路共用，避免修改时遗漏其一。
 */

/** 失败的种类。调用方按它决定提示措辞与后续动作 */
export const FailureKind = {
  /** 连不上服务器（fetch 自己抛错，还没拿到响应） */
  NETWORK: 'network',
  /** 服务端出错（5xx） */
  SERVER: 'server',
  /** 业务上被拒（4xx，含限流），后端给了可读的 msg */
  BUSINESS: 'business',
  /** 流已建立后中途中断：用户可能已收到部分内容 */
  INTERRUPTED: 'interrupted'
}

export class AiStreamError extends Error {
  /**
   * @param kind    {@link FailureKind} 之一
   * @param message 直接给用户看的话（中文，不含技术细节）
   * @param status  HTTP 状态码；没拿到响应时为 0
   * @param code    后端响应体里的业务错误码（4xx 时通常有）
   */
  constructor(kind, message, { status = 0, code = null } = {}) {
    super(message)
    this.name = 'AiStreamError'
    this.kind = kind
    this.status = status
    this.code = code
  }
}

/**
 * 对「开流之前的失败」分类。
 *
 * <p>分界线为 500：5xx 表示请求本身有效、问题在服务端，用户重试无意义；
 * 4xx 表示本次请求不被接受，后端通常已返回可读提示，直接采用即可。
 *
 * @param status  HTTP 状态码
 * @param payload 已解析的响应体（可能为 null，网关错误页不是 JSON）
 */
export function fromHttpStatus(status, payload) {
  const backendMsg = payload && typeof payload.msg === 'string' && payload.msg ? payload.msg : null
  const code = payload && typeof payload.code === 'number' ? payload.code : null

  if (status >= 500) {
    // 不透出后端技术信息：500 的响应体可能是堆栈或网关 HTML，
    // 用户无法从中获得有效信息，且会误判为网站故障
    return new AiStreamError(FailureKind.SERVER, `服务器出错了（${status}），请稍后重试`, { status })
  }
  if (backendMsg) {
    return new AiStreamError(FailureKind.BUSINESS, backendMsg, { status, code })
  }
  // 4xx 但响应体不是 JSON（或没有 msg）：仍然按业务错处理，给一句中性的
  return new AiStreamError(FailureKind.BUSINESS, `请求没有被接受（${status}），请稍后重试`, { status })
}

/**
 * fetch 自身抛错（尚未拿到响应）。
 *
 * <p>用户主动停止不属于失败，由调用方原样抛出；此处仅处理真实的网络问题。
 */
export function fromFetchError(error) {
  return new AiStreamError(FailureKind.NETWORK, '连不上服务器，请检查网络后重试', {
    status: 0,
    code: null
  })
}

/**
 * 流已建立后中途中断。
 *
 * <p>HTTP 层无法判别（开流时即返回 200），只能根据 {@code reader.read()} 抛错推断。
 * 文案按是否已收到内容分为两种：有内容时需说明「已生成的部分可以保留」，
 * 否则用户会认为内容丢失并重新生成，从而再次扣减额度。
 *
 * @param received 已收到的正文字符数
 */
export function fromStreamBreak(received) {
  const message = received > 0
    ? '生成中断了，已经生成的部分可以保留，需要的话再点一次'
    : '生成中断了，请稍后重试'
  return new AiStreamError(FailureKind.INTERRUPTED, message, { status: 200 })
}

/** 是否为用户主动停止：不属于错误，不走错误提示 */
export function isAbort(error) {
  return error?.name === 'AbortError'
}
