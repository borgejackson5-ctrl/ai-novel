import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import * as ElementPlusIcons from '@element-plus/icons-vue'

/**
 * 图标名集合（`@element-plus/icons-vue` 导出的全部名字）。
 *
 * <p>仅在构建进程中使用（判断模板中的 <Search /> 是否为图标），
 * 不进入产物；产物中每个页面只包含自身使用到的图标。
 */
const ICON_NAMES = new Set(Object.keys(ElementPlusIcons))

/**
 * 让模板中的图标（{@code <el-icon><Search /></el-icon>}）按需引入。
 *
 * <p>原方案在 main.js 中将整个图标库注册为全局组件（`import * as` + 逐个 app.component），
 * 首屏体积增加 154 KB，而全站仅使用 37 个图标。
 *
 * <p>使用 resolver 而非手写 import 清单的原因：模板中的图标以短名字书写，
 * 手写清单需随模板同步维护，遗漏时不报错（图标不渲染，需逐页排查才能发现）。
 * resolver 在编译期解析，名字匹配即引入，不匹配则报「组件未注册」，控制台可见。
 *
 * <p>注意：resolver 不处理 {@code <component :is="'Bell'" />} 这类动态用法（相关 6 处已改为传组件对象，
 * 见各文件注释）。该改法为有意选择：字符串形式依赖全局注册，而全局注册即本次移除的对象。
 */
function ElementPlusIconResolver(name) {
  if (ICON_NAMES.has(name)) {
    return { name, from: '@element-plus/icons-vue' }
  }
}

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入。
    //
    // 背景：首屏 index.js 1250 KB + index.css 358 KB，其中 element-plus 全量包
    // （index.full.min.js 1032 KB + index.css 352 KB）占八成，而项目实际仅使用
    // 三十余种组件。全量引入会将整个组件库加载给每个访客。
    //
    // 以下两个插件在编译期完成两件事，模板无需任何改动：
    //   · Components：遇到 <el-button> 即自动 import 该组件（全项目三十多个页面，
    //     改为手工 import 既易遗漏、又会在新增页面时忘记处理）；
    //   · AutoImport：遇到 ElMessage 这类函数式 API 自动 import，并同时注入其样式。
    // dts: false：项目为纯 JS、无 tsconfig，生成的 .d.ts 文件仅占位而无实际用途。
    AutoImport({ resolvers: [ElementPlusResolver()], dts: false }),
    Components({
      resolvers: [ElementPlusResolver(), ElementPlusIconResolver],
      dts: false
    })
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        // 默认对接本地 IDE 起的后端(8080)；对接 Docker 后端时 BACKEND=http://localhost:8081 npm run dev
        target: process.env.BACKEND || 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
