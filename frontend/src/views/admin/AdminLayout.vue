<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '../../store/user'
import { getPendingAuditCount, getFeedbackPendingCount } from '../../api'
import NoticeBell from '../../components/NoticeBell.vue'
import { ChatDotRound, CircleCheck, Collection, CollectionTag, DataAnalysis, MagicStick, Memo, Odometer, Tickets, Upload, User } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const titleMap = {
  '/admin/dashboard': '数据看板',
  '/admin/audit': '内容审核',
  '/admin/novels': '小说管理',
  '/admin/categories': '分类管理',
  '/admin/users': '用户管理',
  '/admin/orders': '订单管理',
  '/admin/feedback': '反馈管理',
  '/admin/appeals': '作品申请',
  '/admin/import': '公版书导入',
  '/admin/logs': '操作日志',
  '/admin/ai-config': 'AI 配置',
  '/admin/system': '系统健康'
}
const pageTitle = computed(() => titleMap[route.path] || '管理后台')

// 侧栏按「待办 → 内容管理 → 工具 → 系统」分组：使用频率最高的待审队列置于最前
const MENU_GROUPS = [
  {
    title: '待办',
    items: [
      { to: '/admin/audit', icon: CircleCheck, label: '内容审核', badge: 'pending' },
      { to: '/admin/appeals', icon: Tickets, label: '作品申请' }
    ]
  },
  {
    title: '内容管理',
    items: [
      { to: '/admin/dashboard', icon: DataAnalysis, label: '数据看板' },
      { to: '/admin/novels', icon: Collection, label: '小说管理' },
      { to: '/admin/categories', icon: CollectionTag, label: '分类管理' },
      { to: '/admin/users', icon: User, label: '用户管理' },
      { to: '/admin/orders', icon: Tickets, label: '订单管理' },
      { to: '/admin/feedback', icon: ChatDotRound, label: '反馈管理', badge: 'feedback' }
    ]
  },
  {
    title: '工具',
    items: [
      { to: '/admin/import', icon: Upload, label: '公版书导入' }
    ]
  },
  {
    title: '系统',
    items: [
      { to: '/admin/ai-config', icon: MagicStick, label: 'AI 配置' },
      { to: '/admin/logs', icon: Memo, label: '操作日志' },
      { to: '/admin/system', icon: Odometer, label: '系统健康' }
    ]
  }
]

// 侧栏角标：key 与 MENU_GROUPS 中的 badge 对应。
// 两者均为「待办」语义：阅读站内信不会使其归零，仅在真正处理后减少。
const badgeCounts = ref({ pending: 0, feedback: 0 })
let timer = null
const loadBadges = async () => {
  try {
    const [audit, feedback] = await Promise.all([getPendingAuditCount(), getFeedbackPendingCount()])
    badgeCounts.value = { pending: Number(audit) || 0, feedback: Number(feedback) || 0 }
  } catch (e) { /* 非管理员或未登录时忽略 */ }
}
onMounted(() => {
  loadBadges()
  timer = setInterval(loadBadges, 30000)
})
onBeforeUnmount(() => timer && clearInterval(timer))

const logout = () => {
  userStore.logout()
  router.push('/login')
}

// 窄屏侧栏抽屉：≤768px 时侧栏移出屏幕外，由 topbar 左侧按钮控制开关
// （桌面端按钮不显示、侧栏仍保留在文档流中，相关规则均在样式末尾的媒体查询内）。
const sideOpen = ref(false)
// 点击菜单即关闭：抽屉保持打开会遮挡刚打开的内容
watch(() => route.path, () => { sideOpen.value = false })
// 抽屉打开时锁定背景滚动：否则在移动端于抽屉上滑动会带动背后页面一同滚动
watch(sideOpen, (v) => { document.body.style.overflow = v ? 'hidden' : '' })
// 组件卸载时还原，否则离开后台后整个站点都无法滚动
onBeforeUnmount(() => { document.body.style.overflow = '' })
</script>

<template>
  <div class="admin-shell">
    <aside class="sidebar" :class="{ open: sideOpen }">
      <div class="brand">
        <span class="brand-logo"><el-icon><Reading /></el-icon></span>
        <div class="brand-text">
          <div class="brand-title">灵阅管理后台</div>
          <div class="brand-sub">内容审核与运营</div>
        </div>
      </div>

      <nav class="menu">
        <template v-for="g in MENU_GROUPS" :key="g.title">
          <div class="menu-group">{{ g.title }}</div>
          <router-link v-for="m in g.items" :key="m.to" :to="m.to" class="menu-item">
            <span class="mi-icon"><el-icon><component :is="m.icon" /></el-icon></span>
            <span>{{ m.label }}</span>
            <span v-if="m.badge && badgeCounts[m.badge] > 0" class="mi-badge">
              {{ badgeCounts[m.badge] > 99 ? '99+' : badgeCounts[m.badge] }}
            </span>
          </router-link>
        </template>
      </nav>

      <div class="sidebar-foot">
        <span class="me-avatar">{{ (userStore.nickname || userStore.username || 'A').slice(0, 1) }}</span>
        <div class="me-text">
          <div class="me-name">{{ userStore.nickname || userStore.username }}</div>
          <div class="who">@{{ userStore.username }}</div>
        </div>
      </div>
    </aside>

    <!-- 窄屏抽屉的遮罩：点击关闭抽屉。z-index 低于侧栏，使侧栏位于其上 -->
    <div v-if="sideOpen" class="side-mask" @click="sideOpen = false"></div>

    <div class="main-col">
      <header class="topbar">
        <div class="crumb">
          <button class="menu-btn" type="button" aria-label="打开菜单" @click="sideOpen = true">
            <el-icon><Menu /></el-icon>
          </button>
          <span class="crumb-root">管理后台</span>
          <span class="crumb-sep">/</span>
          <span class="crumb-cur">{{ pageTitle }}</span>
        </div>
        <div class="topbar-right">
          <div v-if="badgeCounts.pending > 0" class="todo-chip" @click="router.push('/admin/audit')">
            <el-icon><Bell /></el-icon>
            <span>待审核 {{ badgeCounts.pending }}</span>
          </div>
          <NoticeBell />
          <el-button size="small" type="danger" plain @click="logout">退出</el-button>
        </div>
      </header>
      <main class="content">
        <el-config-provider size="small">
          <router-view />
        </el-config-provider>
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-shell { display: flex; min-height: 100vh; background: var(--bg); }

/* ===== 墨色侧边栏 ===== */
.sidebar {
  width: 228px; flex-shrink: 0;
  background: #1c1a17;
  color: #f2ede4; display: flex; flex-direction: column;
  position: sticky; top: 0; height: 100vh;
}
.brand {
  display: flex; align-items: center; gap: 12px;
  padding: 20px 20px 18px; border-bottom: 1px solid rgba(242, 237, 228, 0.1);
}
.brand-logo {
  width: 32px; height: 32px; border-radius: 8px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: var(--cinnabar); color: #fff; font-size: 17px;
}
.brand-title { font-family: var(--serif); font-size: 15px; font-weight: 600; letter-spacing: 0.5px; }
.brand-sub { font-size: 11px; color: rgba(242, 237, 228, 0.45); margin-top: 3px; letter-spacing: 0.5px; }

.menu { flex: 1; padding: 14px 12px; overflow-y: auto; }
.menu-group {
  font-size: 11px; letter-spacing: 1px;
  color: rgba(242, 237, 228, 0.38);
  padding: 14px 12px 6px;
}
.menu-group:first-child { padding-top: 4px; }
.menu-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 12px; border-radius: 7px;
  color: rgba(242, 237, 228, 0.72); text-decoration: none; font-size: 14px;
  transition: background .18s, color .18s;
}
.menu-item:hover { color: #fff; background: rgba(242, 237, 228, 0.07); }
.menu-item.router-link-active { color: #fff; background: var(--cinnabar); }
.mi-icon { font-size: 15px; width: 20px; display: flex; justify-content: center; }
.mi-badge {
  margin-left: auto; flex-shrink: 0;
  min-width: 19px; height: 19px; padding: 0 6px;
  border-radius: 10px; background: #ff4d4f; color: #fff;
  font-size: 11px; font-weight: 500; line-height: 19px; text-align: center;
}
.menu-item.router-link-active .mi-badge { background: rgba(255, 255, 255, 0.9); color: var(--cinnabar); }

.sidebar-foot {
  padding: 14px 18px; border-top: 1px solid rgba(242, 237, 228, 0.1);
  display: flex; align-items: center; gap: 10px;
}
.me-avatar {
  width: 30px; height: 30px; border-radius: 50%; flex-shrink: 0;
  background: var(--cinnabar); color: #fff; font-size: 13px;
  display: flex; align-items: center; justify-content: center;
}
.me-text { min-width: 0; }
.me-name { font-size: 13px; color: rgba(242, 237, 228, 0.9); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.who { color: rgba(242, 237, 228, 0.42); font-size: 11.5px; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ===== 右侧主区 ===== */
.main-col { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.topbar {
  height: 56px; background: var(--paper-2); border-bottom: 1px solid var(--line);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 24px; position: sticky; top: 0; z-index: 50;
}
.crumb { display: flex; align-items: center; gap: 8px; font-size: 13.5px; }
.crumb-root { color: var(--muted); }
.crumb-sep { color: var(--line); }
.crumb-cur { color: var(--ink); font-weight: 500; }
.topbar-right { display: flex; align-items: center; gap: 8px; }
.todo-chip {
  display: flex; align-items: center; gap: 6px;
  font-size: 12.5px; color: var(--cinnabar);
  background: var(--cinnabar-bg);
  padding: 4px 12px; border-radius: 6px; cursor: pointer;
  transition: background .18s;
}
.todo-chip:hover { background: #f0d8d5; }

/* width: 100% 不可省略：column flex 下该区域会被内容收缩为 fit-content，
   表格列较少时右侧出现大片空白（分类管理 5 列时仅占 748/1212）。 */
.content { flex: 1; width: 100%; padding: 20px 24px 28px; overflow: auto; }
.content :deep(.el-table) { font-size: 13px; }

/* 汉堡按钮：只在窄屏出现（桌面端侧栏常驻，不需要它） */
.menu-btn {
  display: none; align-items: center; justify-content: center;
  width: 32px; height: 32px; padding: 0; flex-shrink: 0;
  border: 1px solid var(--line); border-radius: 7px;
  background: var(--paper-2); color: var(--ink);
  cursor: pointer; font-size: 16px;
}
.side-mask { display: none; }

/* ===== 窄屏（手机）=====
   侧栏固定 228px 且 flex-shrink:0，390px 屏幕下主区仅剩 162px，
   「待办 / 内容审核 / 作品申请」逐字竖排、卡片中的数字亦竖排，
   而整页文档宽度仍为 390（无横向滚动，因此仅检查 scrollWidth 无法发现）。
   改为抽屉：侧栏移出屏幕外，由 topbar 左侧按钮唤出。 */
@media (max-width: 768px) {
  .sidebar {
    position: fixed; left: 0; top: 0; bottom: 0;
    height: 100vh; height: 100dvh;      /* dvh 避开手机地址栏收起/展开导致的跳动 */
    z-index: 160;                        /* 要盖住 z-index:50 的 topbar */
    transform: translateX(-100%);
    transition: transform .25s ease;
    box-shadow: 2px 0 16px rgba(0, 0, 0, .22);
  }
  .sidebar.open { transform: none; }
  .side-mask {
    display: block; position: fixed; inset: 0; z-index: 150;
    background: rgba(0, 0, 0, .42);
  }
  .menu-btn { display: flex; }
  .crumb-root, .crumb-sep { display: none; }   /* 面包屑只留当前页名 */
  .main-col { width: 100%; }
  .topbar { padding: 0 12px; }
  .content { padding: 14px 12px 22px; }
}
</style>
