import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'

const routes = [
  { path: '/login', name: 'Login', component: () => import('../views/Login.vue') },
  // meta.guest：未登录也可进入（读者侧只读页面）。与后端 SaTokenConfig 的游客可读白名单
  // 一一对应：后端已放行而前端仍拦截时，等同于未放开
  { path: '/', name: 'Home', component: () => import('../views/Home.vue'), meta: { guest: true } },
  { path: '/novel', name: 'Novel', component: () => import('../views/Novel.vue'), meta: { guest: true } },
  { path: '/rank', name: 'Rank', component: () => import('../views/Rank.vue'), meta: { guest: true } },
  { path: '/shelf', name: 'Shelf', component: () => import('../views/Shelf.vue') },
  { path: '/novel/:id', name: 'NovelDetail', component: () => import('../views/NovelDetail.vue'), meta: { guest: true } },
  { path: '/novel/:id/edit', name: 'NovelEdit', component: () => import('../views/NovelEdit.vue') },
  { path: '/novel/:id/chapter/:cid', name: 'Reader', component: () => import('../views/Reader.vue'), meta: { guest: true } },
  { path: '/novel/:id/manage', name: 'ChapterManage', component: () => import('../views/ChapterManage.vue') },
  { path: '/novel/:id/review', name: 'NovelReview', component: () => import('../views/AiReview.vue') },
  { path: '/novel/:id/stats', name: 'AuthorStats', component: () => import('../views/AuthorStats.vue') },
  { path: '/author/:id', name: 'Author', component: () => import('../views/Author.vue'), meta: { guest: true } },
  { path: '/create', name: 'Create', component: () => import('../views/Create.vue') },
  { path: '/my-works', name: 'MyWorks', component: () => import('../views/MyWorks.vue') },
  { path: '/settings', name: 'Settings', component: () => import('../views/Settings.vue') },
  { path: '/profile', name: 'Profile', component: () => import('../views/Profile.vue') },
  { path: '/wallet', name: 'Wallet', component: () => import('../views/Wallet.vue') },
  { path: '/history', name: 'History', component: () => import('../views/History.vue') },
  { path: '/messages', name: 'Messages', component: () => import('../views/Messages.vue') },
  { path: '/feedback', name: 'Feedback', component: () => import('../views/Feedback.vue') },
  {
    path: '/admin',
    component: () => import('../views/admin/AdminLayout.vue'),
    redirect: '/admin/dashboard',
    meta: { requiresAdmin: true },
    children: [
      { path: 'dashboard', name: 'AdminDashboard', component: () => import('../views/admin/Dashboard.vue') },
      { path: 'audit', name: 'AdminAudit', component: () => import('../views/admin/AuditAdmin.vue') },
      { path: 'novels', name: 'AdminNovel', component: () => import('../views/admin/NovelAdmin.vue') },
      { path: 'categories', name: 'AdminCategory', component: () => import('../views/admin/CategoryAdmin.vue') },
      // 管理员复用前台的消息页（同一组件）。管理员被路由守卫限制在 /admin 下，无法进入 /messages，
      // 因此需在此处再挂载一次，否则铃铛中的「全部消息」对其为无效链接。
      { path: 'messages', name: 'AdminMessages', component: () => import('../views/Messages.vue') },
      { path: 'import', name: 'AdminImport', component: () => import('../views/admin/ImportTool.vue') },
      { path: 'users', name: 'AdminUser', component: () => import('../views/admin/UserAdmin.vue') },
      { path: 'orders', name: 'AdminOrder', component: () => import('../views/admin/OrderAdmin.vue') },
      { path: 'feedback', name: 'AdminFeedback', component: () => import('../views/admin/FeedbackAdmin.vue') },
      { path: 'appeals', name: 'AdminAppeal', component: () => import('../views/admin/AppealAdmin.vue') },
      { path: 'logs', name: 'AdminLog', component: () => import('../views/admin/AdminLog.vue') },
      { path: 'ai-config', name: 'AdminAiConfig', component: () => import('../views/admin/AiConfigAdmin.vue') },
      { path: 'system', name: 'AdminSystem', component: () => import('../views/admin/SystemMetrics.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 角色探测只做一次：避免「后端未返回 role」的账号每次跳转都多打一次接口
let roleProbed = false

// 路由守卫：
// 1) 未登录一律跳转登录页
// 2) 管理员账号仅用于后台：前台页面（首页/书库/书架/我的/反馈/创作等）对其不可达，
//    直接返回 /admin。管理员为审核方，若可进入前台将出现「同一人提交反馈并自行审核」的角色错位
// 3) /admin 仅管理员可进
router.beforeEach(async (to) => {
  // 凭证统一从 store 读取（store 是 token/role 的唯一权威入口，不再散落读 localStorage）
  const { useUserStore } = await import('../store/user')
  const userStore = useUserStore()

  if (to.path === '/login') return true

  // 读者侧只读页面放行给游客（meta.guest）：新用户无需注册即可浏览书库、查看详情、试读免费章节。
  // 与后端 SaTokenConfig 的游客可读白名单一一对应：后端已放行而前端仍拦截时，等同于未放开
  if (!userStore.token && to.meta?.guest) return true

  if (!userStore.token) return '/login'

  // role 可能因本地存储被清理而缺失，进入角色判定前补一次
  if (!userStore.role && !roleProbed) {
    roleProbed = true
    try {
      await userStore.fetchUserInfo()
    } catch (e) {
      userStore.clearSession()
      return '/login'
    }
  }

  const isAdminRoute = to.path.startsWith('/admin')

  if (userStore.role === 'admin') {
    return isAdminRoute ? true : '/admin'
  }

  if (isAdminRoute) {
    ElMessage.warning('仅管理员可访问管理后台')
    return '/'
  }

  return true
})

export default router
