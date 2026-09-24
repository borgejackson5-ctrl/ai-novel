/**
 * 站内信：类型 → 图标 / 点击去向
 *
 * 独立成模块的原因：NoticeBell 与 Messages.vue 原各有一次判断，且均只识别 AUDIT_* 三种，
 * 导致「反馈奖励到账」「申请处理结果」这类消息点击后无任何反应（仅标记已读）。
 * 同一类型需在两处修改，必然遗漏其一。
 *
 * 匹配前统一转小写：反馈奖励早期使用 `feedback_reward`（小写下划线），后规范为
 * `FEEDBACK_RESULT`，数据库中两种历史数据并存，需同时兼容。
 */

import { Bell, ChatDotRound, ChatLineSquare, CircleCheck, CircleClose, Coin, Promotion, Tickets } from '@element-plus/icons-vue'

const ICONS = {
  audit_submit: Promotion,
  audit_pass: CircleCheck,
  audit_reject: CircleClose,
  feedback_submit: ChatDotRound,
  feedback_result: ChatLineSquare,
  feedback_reward: Coin,
  appeal_result: Tickets
}

const ROUTES = {
  audit_submit: { admin: '/admin/audit' },
  audit_pass: { user: '/my-works' },
  audit_reject: { user: '/my-works' },
  feedback_submit: { admin: '/admin/feedback' },
  feedback_result: { user: '/feedback' },
  feedback_reward: { user: '/feedback' },
  appeal_result: { user: '/my-works' }
}

/** 消息图标（未知类型回退通用铃铛） */
export function messageIcon(type) {
  return ICONS[String(type || '').toLowerCase()] || Bell
}

/**
 * 点这条消息该去哪个页面。
 *
 * <p>返回空表示没有对应页面，调用方保持原样即可（不要跳转到不存在的路由）。
 * 同一条消息对管理员与普通用户的去向不同：例如「有人提交了作品」管理员进入审核页，
 * 而普通用户本来也收不到这类消息。
 *
 * @param {string} type 消息类型
 * @param {string} role 当前用户角色（admin / user）
 */
export function messageRoute(type, role) {
  const entry = ROUTES[String(type || '').toLowerCase()]
  if (!entry) return null
  return role === 'admin' ? entry.admin || null : entry.user || null
}
