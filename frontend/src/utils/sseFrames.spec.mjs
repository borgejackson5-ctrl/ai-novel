/**
 * sseFrames 的自测：node 直接跑，不需要浏览器、也不需要测试框架。
 *
 *     node frontend/src/utils/sseFrames.spec.mjs
 *
 * 帧解析曾有两份实现，其中 `Create.vue` 里手写的一份漏了 `JSON.parse`。该缺陷不抛异常，
 * 只让生成结果多出一对引号、正文换行变成字面量 `\n`，因此长期未被发现。
 * 抽成纯函数后，这类缺陷可以直接用 node 覆盖。
 */
import assert from 'node:assert/strict'
import { decodeFrame, createFrameDecoder } from './sseFrames.js'

// ① 正常帧：内容是 JSON 字符串字面量，正文换行为字面量 \n（两个字符）
assert.equal(decodeFrame('data:"他走了。"'), '他走了。')
assert.equal(decodeFrame('data:"第一段\\n\\n第二段"'), '第一段\n\n第二段',
    '正文里的换行必须还原成真换行 —— 直接 slice 拼接会留下字面的反斜杠 n')

// ② 冒号后允许一个空格（SSE 规范）；行尾可能被代理换成 \r\n
assert.equal(decodeFrame('data: "abc"'), 'abc')
assert.equal(decodeFrame('data:"abc"\r'), 'abc')

// ③ 非数据帧返回 undefined：心跳 / 注释帧不能被当成「空字符串内容」塞进正文
assert.equal(decodeFrame(': keep-alive'), undefined)
assert.equal(decodeFrame('event:ping'), undefined)

// ④ 坏帧抛错而不拼原文：拼进去会生成带引号的异常文本，作者难以察觉
assert.throws(() => decodeFrame('data:不是JSON'), /传输格式/)

// ⑤ 增量解析：一帧被网络分片切成两半，第一片不能吐出任何东西
const d1 = createFrameDecoder()
assert.deepEqual(d1.feed('data:"前半'), [])
assert.deepEqual(d1.feed('段"\n\ndata:"后半段"\n\n'), ['前半段', '后半段'])

// ⑥ 一次收到多帧
const d2 = createFrameDecoder()
assert.deepEqual(d2.feed('data:"a"\n\ndata:"b"\n\n'), ['a', 'b'])

// ⑦ 收尾时残留的半截帧留在 buffer 中，不作为内容输出：宁可少一段，不输出半句坏 JSON
const d3 = createFrameDecoder()
assert.deepEqual(d3.feed('data:"完整"\n\ndata:"半'), ['完整'])

console.log('sseFrames：7 组断言全过')
