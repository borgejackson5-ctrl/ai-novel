// 静态检查：模板/配置里用到的图标名，是不是都真的存在于 @element-plus/icons-vue 里。
//
// 跑法： node scripts/verify-icon-names.mjs
//
// 图标从全局注册改为编译期按需引入之后，拼错的名字不再有任何提示：
// 全局注册时它至少是未注册组件（控制台会告警），按需引入时 resolver 匹配不上，
// 会当成普通标签渲染成空白。逐页人工核对容易漏，因此用脚本核对名字这一层。
import fs from 'node:fs'
import path from 'node:path'
import * as Icons from '@element-plus/icons-vue'

const SRC = 'src'
const KNOWN = new Set(Object.keys(Icons))

/** 递归收集 .vue / .js */
function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (/\.(vue|js)$/.test(e.name)) out.push(p)
  }
  return out
}

const files = walk(SRC)
const used = new Map() // 名字 -> 出现位置

for (const f of files) {
  const text = fs.readFileSync(f, 'utf8')

  // 模板里的 <SomeIcon />（只取自闭合的大写开头标签，普通组件如 <WorkbenchTabs /> 也会进来，
  // 所以下面只核对「看起来像图标名」的那些：库里没有、但模板里有大写标签的，另行汇报）
  for (const m of text.matchAll(/<([A-Z][A-Za-z0-9]*)\s*\/>/g)) {
    const name = m[1]
    if (!used.has(name)) used.set(name, [])
    used.get(name).push(f)
  }
  // 改过的动态用法：icon: SomeIcon
  for (const m of text.matchAll(/icon:\s*([A-Z][A-Za-z0-9]*)/g)) {
    const name = m[1]
    if (!used.has(name)) used.set(name, [])
    used.get(name).push(f)
  }
  // 纯 js 里的图标映射值（utils/message.js 那种 `key: SomeIcon,`）
  for (const m of text.matchAll(/^\s+\w+:\s*([A-Z][A-Za-z0-9]*),?$/gm)) {
    const name = m[1]
    if (!used.has(name)) used.set(name, [])
    used.get(name).push(f)
  }
}

const missing = []
const okIcons = []
const others = []
for (const [name, where] of used) {
  if (KNOWN.has(name)) okIcons.push(name)
  else if (/^[A-Z][A-Za-z0-9]*$/.test(name)) others.push([name, where])
}

console.log('图标库里可用的名字：' + KNOWN.size + ' 个')
console.log('项目里用到且能对上库的：' + okIcons.length + ' 个')
console.log('  ' + okIcons.sort().join(', '))

// 剩下的「大写标签但不在图标库里」：可能是不在本项目的自定义组件，也可能是写错的图标名，
// 逐一列出供人工确认，不自动判定为错误。
// 路径分隔符在 Windows 上是 \，正则里两种都要认，否则会把自定义组件误报成图标名。
const localComponents = new Set(
  files
    .filter((f) => /components[\\/].*\.vue$/.test(f))
    .map((f) => path.basename(f, '.vue'))
)
const suspicious = others.filter(([name]) => !localComponents.has(name))

console.log('\n本项目的自定义组件（不是图标，正常）：' +
  others.filter(([n]) => localComponents.has(n)).map(([n]) => n).join(', '))

if (suspicious.length) {
  console.log('\n⚠️ 既不像本项目的组件、也不在图标库里 —— 可能是写错的图标名（会很安静地消失）：')
  for (const [name, where] of suspicious) {
    console.log('   ' + name + '   <-- ' + [...new Set(where)].join(', '))
  }
  process.exit(1)
}
console.log('\n没有可疑的图标名 ✓')
