<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from './store/user'
import { useReaderStore } from './store/reader'
import NoticeBell from './components/NoticeBell.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const readerStore = useReaderStore()

const keyword = ref(String(route.query.keyword || ''))

// 刷新后 token 仍在 localStorage，但 store 的余额/昵称会重置为默认值，需重新拉取
onMounted(() => {
  if (userStore.token) {
    userStore.fetchUserInfo().catch(() => {})
  }
})

// 从书库页返回别的页面时，输入框跟随地址栏同步
watch(
  () => route.query.keyword,
  (v) => { keyword.value = String(v || '') }
)

const isLoginPage = () => route.path === '/login'
const isAdminPage = () => route.path.startsWith('/admin')

const NAV = [
  { path: '/', label: '首页' },
  { path: '/novel', label: '书库' },
  { path: '/rank', label: '排行榜' },
  { path: '/shelf', label: '书架' },
  { path: '/profile', label: '我的' }
]
const MINE_PATHS = ['/profile', '/wallet', '/history', '/messages', '/create', '/my-works', '/settings', '/feedback']

// 作者工作台各页（章节管理 / 全书审查 / 作品信息 / 作品数据）位于 /novel/:id/... 下，但语义属于「我的作品」。
// 不能仅向 MINE_PATHS 添加字符串：isActive 使用 startsWith 判断，无法匹配带参数的路径，
// 会导致「我的」不高亮而「书库」高亮。因此单独用正则判断一次，并从「书库」的匹配中排除。
const WORKBENCH_RE = /^\/novel\/[^/]+\/(edit|manage|stats|review)$/
const isWorkbench = (p) => WORKBENCH_RE.test(p)

const isActive = (path) => {
  if (path === '/') return route.path === '/'
  if (path === '/profile') return MINE_PATHS.some((p) => route.path.startsWith(p)) || isWorkbench(route.path)
  return route.path.startsWith(path) && !isWorkbench(route.path)
}

const go = (path) => router.push(path)

const doSearch = () => {
  const kw = keyword.value.trim()
  router.push(kw ? { path: '/novel', query: { keyword: kw } } : '/novel')
}

const logout = async () => {
  await userStore.logout()
  router.push('/login')
}

const onUserCmd = (cmd) => {
  if (cmd === 'logout') logout()
  else router.push(cmd)
}
</script>

<template>
  <div class="app">
    <header v-if="!isLoginPage() && !isAdminPage() && !readerStore.immersive" class="header">
      <div class="logo" @click="go('/')">
        <span class="logo-mark"><el-icon><Reading /></el-icon></span>
        <span class="logo-text">灵阅</span>
      </div>

      <nav class="nav">
        <span
          v-for="n in NAV"
          :key="n.path"
          :class="{ active: isActive(n.path) }"
          @click="go(n.path)"
        >{{ n.label }}</span>
      </nav>

      <div class="search">
        <el-icon class="search-ic"><Search /></el-icon>
        <input v-model="keyword" placeholder="搜索书名 / 作者" @keyup.enter="doSearch" />
      </div>

      <div class="user">
        <!-- 游客（未登录）：不显示「0 金币」和空白头像，二者均为登录后才有，
             显示会使用户误判账号状态异常。改为直接提供登录入口 -->
        <NoticeBell v-if="userStore.token" />
        <div v-if="userStore.token" class="coin-tag" @click="go('/profile')">
          <el-icon class="coin-icon"><Coin /></el-icon>
          <span>{{ userStore.coinBalance }}</span>
        </div>
        <el-button
          v-if="!userStore.token"
          size="small"
          round
          type="primary"
          @click="go('/login')"
        >登录 / 注册</el-button>
        <el-dropdown v-else @command="onUserCmd">
          <div class="avatar">{{ (userStore.nickname || userStore.username || 'U').slice(0, 1) }}</div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>@{{ userStore.username }}</el-dropdown-item>
              <el-dropdown-item command="/shelf" divided>我的书架</el-dropdown-item>
              <el-dropdown-item command="/my-works">我的作品</el-dropdown-item>
              <el-dropdown-item command="/create">创作发布</el-dropdown-item>
              <el-dropdown-item command="/settings">AI 设置</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>
    <main :class="{ 'no-header': isLoginPage(), 'no-shell': isAdminPage(), 'immersive': readerStore.immersive }">
      <router-view />
    </main>
  </div>
</template>

<style>
/* ===================================================================
   设计 Token · 书卷纸墨
   墨为字、纸为底、朱砂为点睛。全局改色只需改这一段。
   =================================================================== */
:root {
  --paper: #f7f4ed;
  --paper-2: #fffdf9;
  --ink: #1c1a17;
  --ink-2: #3a352e;
  --line: #e3ddd0;
  --line-soft: #efeade;
  --cinnabar: #b23a2e;
  --cinnabar-d: #8e2c22;
  --cinnabar-bg: #f6e7e3;

  /* 兼容旧变量名：全站页面依赖这些名字，改值即可整体换色 */
  --primary: #b23a2e;
  --primary-light: #c4553f;
  --bg: #f7f4ed;
  --card: #fffdf9;
  --text: #1c1a17;
  --muted: #8a7f6d;
  --gold: #a8842c;

  --serif: 'Noto Serif SC', 'Source Han Serif SC', 'Songti SC', 'SimSun', Georgia, serif;

  /* Element Plus 主题覆盖 */
  --el-color-primary: #b23a2e;
  --el-color-primary-light-3: #c9756d;
  --el-color-primary-light-5: #d99d97;
  --el-color-primary-light-7: #e8c4c0;
  --el-color-primary-light-8: #f0d8d5;
  --el-color-primary-light-9: #f7ebea;
  --el-color-primary-dark-2: #8e2e25;
  --el-border-radius-base: 10px;
  --el-border-radius-small: 6px;
  --el-font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', 'Segoe UI', sans-serif;
}

* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', 'Segoe UI', sans-serif;
  background: var(--bg);
  color: var(--text);
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
}

.app { min-height: 100vh; }

/* ===== 顶栏 ===== */
.header {
  height: 64px;
  background: var(--paper-2);
  color: var(--ink);
  display: flex; align-items: center; gap: 24px;
  padding: 0 28px;
  position: sticky; top: 0; z-index: 100;
  border-bottom: 1px solid var(--line);
}
.logo { display: flex; align-items: center; gap: 9px; cursor: pointer; user-select: none; flex-shrink: 0; }
.logo-mark {
  width: 28px; height: 28px; border-radius: 7px;
  background: var(--cinnabar); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 16px;
}
.logo-text { font-family: var(--serif); font-size: 20px; font-weight: 600; letter-spacing: 2px; }
.nav { display: flex; gap: 4px; }
.nav span {
  cursor: pointer; color: var(--muted); font-size: 14.5px;
  padding: 7px 14px; border-radius: 6px;
  transition: color .18s, background .18s;
  white-space: nowrap;
}
.nav span:hover { color: var(--ink); background: var(--paper); }
.nav span.active { color: var(--cinnabar); background: var(--cinnabar-bg); font-weight: 500; }

.search {
  flex: 1; min-width: 140px; max-width: 320px; margin-left: auto;
  display: flex; align-items: center; gap: 8px;
  height: 36px; padding: 0 12px;
  background: var(--paper);
  border: 1px solid var(--line);
  border-radius: 8px;
  transition: border-color .18s, background .18s;
}
.search:focus-within { border-color: var(--cinnabar); background: #fff; }
.search-ic { color: var(--muted); font-size: 15px; flex-shrink: 0; }
.search input {
  flex: 1; min-width: 0; border: none; outline: none; background: transparent;
  font-size: 13.5px; color: var(--ink); font-family: inherit;
}
.search input::placeholder { color: var(--muted); }

.user { display: flex; align-items: center; gap: 14px; flex-shrink: 0; }
.coin-tag {
  display: flex; align-items: center; gap: 5px;
  background: #fbf3e2; color: #8a6a20; border: 1px solid #efe3c8;
  padding: 5px 12px; border-radius: 8px; font-weight: 500; cursor: pointer;
  font-size: 13.5px;
}
.coin-icon { font-size: 15px; }
.avatar {
  width: 32px; height: 32px; border-radius: 50%;
  background: var(--cinnabar); color: #fff;
  display: flex; align-items: center; justify-content: center;
  font-weight: 500; cursor: pointer; font-size: 14px;
}

main { padding: 24px 28px; max-width: 1200px; margin: 0 auto; }
main.no-header { padding: 0; max-width: none; }
main.no-shell { padding: 0; max-width: none; min-height: 100vh; }
main.immersive { padding: 0; max-width: none; }

/* ===== 通用楼层 / 卡片标题 ===== */
.floor { margin-top: 40px; }
.floor-head {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  margin-bottom: 18px;
}
.section-title {
  font-family: var(--serif);
  font-size: 20px; font-weight: 600; color: var(--ink);
  display: flex; align-items: center; gap: 10px;
}
.section-title::before {
  content: ''; width: 3px; height: 18px; border-radius: 2px;
  background: var(--cinnabar);
}
.floor-note { font-size: 13px; color: var(--muted); }
.floor-more {
  margin-left: auto; font-size: 13px; color: var(--cinnabar);
  cursor: pointer; user-select: none;
}
.floor-more:hover { text-decoration: underline; }
.badge {
  font-size: 12px; color: var(--primary);
  background: var(--cinnabar-bg);
  padding: 2px 10px; border-radius: 6px;
}

/* 图标与文字并排时的对齐与间距 */
.el-icon { vertical-align: -0.15em; }
.with-ic { display: inline-flex; align-items: center; gap: 6px; }

/* 书籍列表网格：全站统一用横排书卡 */
.book-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 14px; }

/* 空状态 */
.empty-tip {
  padding: 56px 0; text-align: center;
  color: var(--muted); font-size: 13.5px;
}

@keyframes fadeUp {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

/* ===== 窄屏（手机）=====
   置于样式末尾：媒体查询不提升优先级，必须排在所有普通规则之后，
   否则下方的 main{padding} 会被上方 main{ padding: 24px 28px } 覆盖。
   顶栏原为一行排列 logo + 5 个菜单 + 搜索框 + 用户区，390px 宽下将文档撑到
   796px，导致每一页都需要左右滚动。此处仅收缩三处：菜单改为可横向滚动、搜索框与
   金币数收起。桌面端不受影响（断点仅在 ≤768px 生效）。 */
@media (max-width: 768px) {
  .header { gap: 10px; padding: 0 14px; }
  .nav { flex: 1; overflow-x: auto; scrollbar-width: none; }
  .nav::-webkit-scrollbar { display: none; }
  .nav span { padding: 7px 10px; font-size: 14px; }
  .search { display: none; }
  .coin-tag { display: none; }
  .user { gap: 10px; }
  main { padding: 16px 14px; }
  /* 书卡网格列宽下限为 340px，窄屏下会溢出容器 */
  .book-grid { grid-template-columns: 1fr; }
}
</style>
