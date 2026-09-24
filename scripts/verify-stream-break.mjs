// 验证一项关键假设：服务端在事件流中途切断连接时，fetch 的 reader 是抛错还是正常结束。
//
// 跑法： node scripts/verify-stream-break.mjs
//
// 必须验证的原因：前端「生成中断了，已经生成的部分可以保留」这一提示，
// 前提是中断可被检测到。若 reader 只是正常 done（而非抛错），
// 该分支永远不会被执行，代码看似合理但实为死代码，中断仍表现为静默。
//
// 附带确认第二点：中断时已收到的内容仍然保留。这决定了文案能否声明「可以保留」，
// 表述错误会让用户以为内容全部丢失并重新生成，从而再次扣减额度。
//
// 用 node 启动本机 HTTP 服务器模拟「写两帧后直接 destroy socket」，
// 无需启动后端与浏览器。
import http from 'node:http'

function startServer(mode) {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
      res.write('data: "第一段"\n\n')
      res.write('data: "第二段"\n\n')
      if (mode === 'destroy') {
        // 模拟「服务端处理中途退出」：TCP 直接断开，不发送终止块
        setTimeout(() => res.socket.destroy(), 50)
      } else {
        // 对照组：正常收尾
        setTimeout(() => { res.write('data: "第三段"\n\n'); res.end() }, 50)
      }
    })
    server.listen(0, '127.0.0.1', () => resolve(server))
  })
}

async function readAll(url) {
  const res = await fetch(url)
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let text = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) return { outcome: 'done', text }
      text += decoder.decode(value, { stream: true })
    }
  } catch (e) {
    return { outcome: 'threw', text, error: e }
  }
}

let fail = 0
const check = (name, cond, extra) => {
  console.log((cond ? '  ok   ' : '  FAIL ') + name + (cond || !extra ? '' : '   -> ' + extra))
  if (!cond) fail++
}

// 1) 正常收尾 → 应返回 done
{
  const server = await startServer('end')
  const r = await readAll(`http://127.0.0.1:${server.address().port}/`)
  server.close()
  check('正常收尾 -> reader 正常结束（done）', r.outcome === 'done', r.outcome)
  check('正常收尾 -> 三帧都拿到了', r.text.includes('第一段') && r.text.includes('第三段'), JSON.stringify(r.text))
}

// 2) 中途切断 → 期望抛错，且已收到的内容仍然保留
{
  const server = await startServer('destroy')
  const r = await readAll(`http://127.0.0.1:${server.address().port}/`)
  server.close()
  check('中途掐断 -> reader 抛错（而不是静默 done）', r.outcome === 'threw',
    '实际是 ' + r.outcome + ' —— 若为 done，中断就检测不到，fromStreamBreak 是死代码')
  check('中途掐断 -> 已经收到的两帧还在（所以能告诉用户「可以保留」）',
    r.text.includes('第一段') && r.text.includes('第二段'), JSON.stringify(r.text))
  if (r.error) {
    console.log('       （抛出的原始错误：' + r.error.name + ': ' + r.error.message + '）')
  }
}

console.log(fail === 0 ? '\n结论：中断可检测，且已收到的内容保留 —— interrupted 分支走得到' : '\n有判据没通过')
process.exit(fail === 0 ? 0 : 1)
