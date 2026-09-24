import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 按需引入，非全量引入（全量方式为 import ElementPlus + 全量 CSS + app.use(ElementPlus)，
// 三者合计约 1032 KB JS + 352 KB CSS，占首屏加载量的八成）。按需方案：
//   · 模板中的 <el-xxx>、v-loading 指令、以及 <Search /> 这类图标：
//     由 vite.config.js 的 Components 插件在编译期自动 import 并注入样式；
//   · ElMessage / ElMessageBox 这类在 js 中调用的 API：各页面仍显式 import
//     （它们不是模板组件，插件不处理，这也是保留显式 import 的原因），
//     但样式需在此处补充，否则提示无样式（可显示，但样式缺失）。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
import App from './App.vue'
import router from './router'

const app = createApp(App)

// 注意：整个图标库若注册为全局组件（`import * as` + 逐个 app.component），
// 首屏体积增加 154 KB，而全站仅使用 37 个图标。按需引入方案：
//   · 模板中写 <Search /> 的：由 vite.config.js 的 ElementPlusIconResolver 在编译期引入；
//   · 用 `<component :is="...">` 动态渲染的（消息类型 / 审核状态 / 后台菜单）：
//     这些位置不传字符串图标名，改传组件对象，在各自文件内 import。
// 两类位置都需处理：字符串形式依赖全局注册才能解析，而全局注册即本次移除的对象。
app.use(createPinia())
app.use(router)
app.mount('#app')
