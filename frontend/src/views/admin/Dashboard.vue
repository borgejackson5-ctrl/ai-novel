<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getAdminDashboard, getChapterAuditPage, getFeedbackPage } from '../../api'

const router = useRouter()
const loading = ref(true)
const data = ref(null)

const fmt = (n) => (n == null ? '0' : Number(n).toLocaleString('zh-CN'))
const money = (n) => (n == null ? '0' : Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 0, maximumFractionDigits: 2 }))

const statusTag = (s) => (s === 1 ? 'success' : s === 0 ? 'warning' : 'info')
const statusText = (s) => (s === 1 ? '已支付' : s === 0 ? '待支付' : '已取消')

// 待办队列：看板首屏优先呈现「有哪些待处理事项」
const pendingNovel = computed(() => Number(data.value?.pendingAudit || 0))
const pendingChapter = ref(0)
const pendingFeedback = ref(0)

const todoTotal = computed(() => pendingNovel.value + pendingChapter.value + pendingFeedback.value)

const loadTodo = async () => {
  try {
    const res = await getChapterAuditPage({ pageNum: 1, pageSize: 1, auditStatus: null })
    pendingChapter.value = Number(res?.total || 0)
  } catch (e) { /* 忽略 */ }
  try {
    const res = await getFeedbackPage({ pageNum: 1, pageSize: 1, status: 0 })
    pendingFeedback.value = Number(res?.total || 0)
  } catch (e) { /* 忽略 */ }
}

const load = async () => {
  loading.value = true
  try {
    data.value = await getAdminDashboard()
    loadTodo()
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="dash">
    <div class="todo">
      <span class="todo-title">待办</span>
      <div class="todo-item" :class="{ hot: pendingNovel > 0 }" @click="router.push('/admin/audit')">
        <span class="t-label">待审核作品</span>
        <b class="t-num">{{ fmt(pendingNovel) }}</b>
      </div>
      <div class="todo-item" :class="{ hot: pendingChapter > 0 }" @click="router.push('/admin/audit')">
        <span class="t-label">待审核章节</span>
        <b class="t-num">{{ fmt(pendingChapter) }}</b>
      </div>
      <div class="todo-item" :class="{ hot: pendingFeedback > 0 }" @click="router.push('/admin/feedback')">
        <span class="t-label">待处理反馈</span>
        <b class="t-num">{{ fmt(pendingFeedback) }}</b>
      </div>
      <span class="todo-tail">{{ todoTotal ? '点击进入处理' : '暂无待处理事项' }}</span>
    </div>

    <div class="cards">
      <div class="card">
        <div class="card-ic ic-ink"><el-icon><User /></el-icon></div>
        <div class="card-meta">
          <div class="card-num">{{ fmt(data?.userTotal) }}</div>
          <div class="card-label">注册用户</div>
          <div class="card-sub">今日新增 <b class="up">{{ fmt(data?.userToday) }}</b></div>
        </div>
      </div>
      <div class="card">
        <div class="card-ic ic-cinnabar"><el-icon><Collection /></el-icon></div>
        <div class="card-meta">
          <div class="card-num">{{ fmt(data?.novelTotal) }}</div>
          <div class="card-label">小说总数</div>
          <div class="card-sub">
            上架中 <b>{{ fmt(data?.novelOnline) }}</b>
            <template v-if="data?.pendingAudit > 0">
              · <a class="audit-link" @click="router.push('/admin/audit')">待审核 <b class="audit-hot">{{ fmt(data.pendingAudit) }}</b> ›</a>
            </template>
            <template v-else>· 待审核 <b>0</b></template>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-ic ic-gold"><el-icon><Coin /></el-icon></div>
        <div class="card-meta">
          <div class="card-num">¥ {{ money(data?.rechargeAmount) }}</div>
          <div class="card-label">累计充值金额</div>
          <div class="card-sub">已支付 <b>{{ fmt(data?.rechargeCount) }}</b> 单</div>
        </div>
      </div>
      <div class="card">
        <div class="card-ic ic-moss"><el-icon><Unlock /></el-icon></div>
        <div class="card-meta">
          <div class="card-num">{{ fmt(data?.subscribeCount) }}</div>
          <div class="card-label">解锁订单</div>
          <div class="card-sub">消耗 <b>{{ fmt(data?.subscribeCoin) }}</b> 币</div>
        </div>
      </div>
    </div>

    <div class="cols">
      <section class="panel">
        <div class="panel-head">
          <h3>内容热度 Top5</h3>
          <el-button text size="small" @click="router.push('/admin/novels')">去管理 ›</el-button>
        </div>
        <div v-if="!data?.hotNovels?.length" class="empty">暂无小说数据</div>
        <div v-else class="rank-list">
          <div v-for="(d, i) in data.hotNovels" :key="d.id" class="rank-row" @click="router.push('/novel/' + d.id)">
            <span class="rank-no" :class="{ top: i < 3 }">{{ i + 1 }}</span>
            <span class="rank-cover">{{ d.title.slice(0, 1) }}</span>
            <div class="rank-main">
              <div class="rank-title">
                <span class="rank-name">{{ d.title }}</span>
                <span class="rank-cat">{{ d.categoryName }}</span>
              </div>
              <div class="rank-bar">
                <div class="rank-fill" :style="{ width: Math.max(12, (d.readCount / (data.hotNovels[0].readCount || 1)) * 100) + '%' }"></div>
              </div>
            </div>
            <div class="rank-stats">
              <span class="play">阅读 {{ fmt(d.readCount) }}</span>
              <span class="like">点赞 {{ fmt(d.likeCount) }}</span>
            </div>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <h3>近期订单</h3>
          <el-button text size="small" @click="router.push('/admin/orders')">查看全部 ›</el-button>
        </div>
        <div v-if="!data?.recentOrders?.length" class="empty">暂无订单数据</div>
        <div v-else class="order-list">
          <div v-for="(o, i) in data.recentOrders" :key="i" class="order-row">
            <el-tag :type="o.type === 'RECHARGE' ? 'warning' : 'primary'" size="small" effect="light" class="o-type">
              {{ o.type === 'RECHARGE' ? '充值' : '解锁' }}
            </el-tag>
            <div class="o-main">
              <div class="o-desc">{{ o.desc }}</div>
              <div class="o-user">{{ o.username }} · {{ o.createTime?.replace('T', ' ').slice(0, 16) }}</div>
            </div>
            <div class="o-right">
              <span class="o-amount" :class="{ pay: o.status === 1 }">{{ o.status === 1 ? '+' : '' }}{{ o.type === 'RECHARGE' ? '¥ ' + money(o.amount) : o.amount + ' 币' }}</span>
              <el-tag :type="statusTag(o.status)" size="small">{{ statusText(o.status) }}</el-tag>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.dash { min-height: 400px; }

/* 待办队列：看板首屏优先呈现「有哪些待处理事项」 */
.todo {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  background: var(--paper-2); border: 1px solid var(--line);
  border-radius: 10px; padding: 11px 16px; margin-bottom: 14px;
}
.todo-title {
  font-family: var(--serif); font-size: 14px; font-weight: 600; color: var(--ink);
  padding-right: 10px; border-right: 1px solid var(--line);
}
.todo-item {
  display: flex; align-items: center; gap: 8px;
  padding: 4px 12px; border-radius: 6px; cursor: pointer;
  background: var(--paper);
  transition: background .18s;
}
.todo-item:hover { background: var(--line-soft); }
.todo-item.hot { background: var(--cinnabar-bg); }
.todo-item.hot:hover { background: #f0d8d5; }
.t-label { font-size: 12.5px; color: var(--muted); }
.t-num { font-size: 14px; font-weight: 500; color: var(--ink); }
.todo-item.hot .t-num { color: var(--cinnabar); }
.todo-tail { margin-left: auto; font-size: 12px; color: var(--muted); }

/* 统计卡 */
.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 16px; }
.card {
  background: var(--paper-2); border-radius: 10px; padding: 16px 18px;
  display: flex; gap: 14px; align-items: flex-start;
  border: 1px solid var(--line);
}
.card-ic {
  width: 42px; height: 42px; border-radius: 10px; font-size: 20px;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.card-ic.ic-ink { background: var(--paper); color: var(--ink-2); }
.card-ic.ic-cinnabar { background: var(--cinnabar-bg); color: var(--cinnabar); }
.card-ic.ic-gold { background: #fbf3e2; color: #8a6a20; }
.card-ic.ic-moss { background: #e8f0e6; color: #46603f; }
.card-meta { min-width: 0; }
.card-num {
  font-size: 24px; font-weight: 800; letter-spacing: 0.3px; color: var(--ink);
  font-variant-numeric: tabular-nums; line-height: 1.2;
}
.card-label { font-size: 12.5px; color: var(--muted); margin-top: 2px; }
.card-sub { font-size: 11.5px; color: var(--muted); margin-top: 6px; }
.card-sub b { color: #b23a2e; font-weight: 600; }
.card-sub b.up { color: #e29b2a; }
.audit-link { color: #e29b2a; cursor: pointer; text-decoration: none; }
.audit-link:hover { text-decoration: underline; }
.audit-link .audit-hot { color: #ff4d4f; font-weight: 800; }

/* 两栏 */
.cols { display: grid; grid-template-columns: 1.15fr 1fr; gap: 16px; align-items: start; }
.panel {
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 10px;
  padding: 16px 18px;
}
.panel-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.panel-head h3 { font-family: var(--serif); font-size: 15px; font-weight: 600; color: var(--ink); }
.empty { color: var(--muted); text-align: center; padding: 30px 0; font-size: 13px; }

/* 榜单行 */
.rank-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 6px;
  border-radius: 10px; cursor: pointer; transition: background .15s;
}
.rank-row:hover { background: var(--paper); }
.rank-no {
  width: 22px; height: 22px; border-radius: 7px; flex-shrink: 0;
  background: var(--paper); color: #6b6f85; font-size: 12px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.rank-no.top { background: var(--cinnabar); color: #fff; }
.rank-cover {
  width: 34px; height: 34px; border-radius: 6px; flex-shrink: 0;
  background: var(--ink); color: var(--paper-2);
  display: flex; align-items: center; justify-content: center;
  font-size: 15px; font-weight: 600;
}
.rank-main { flex: 1; min-width: 0; }
.rank-title { display: flex; align-items: center; gap: 6px; min-width: 0; }
.rank-name { font-size: 13.5px; font-weight: 600; color: var(--ink); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rank-cat { font-size: 11px; color: #b23a2e; background: #f6e7e3; padding: 1px 7px; border-radius: 10px; flex-shrink: 0; }
.rank-bar { height: 4px; background: var(--paper); border-radius: 2px; margin-top: 6px; overflow: hidden; }
.rank-fill { height: 100%; background: var(--cinnabar); border-radius: 2px; }
.rank-stats { flex-shrink: 0; text-align: right; font-size: 11.5px; }
.rank-stats .play { color: #b23a2e; font-weight: 600; margin-right: 6px; }
.rank-stats .like { color: #d4537e; }

/* 订单行 */
.order-list { display: flex; flex-direction: column; }
.order-row {
  display: flex; align-items: center; gap: 10px; padding: 8px 4px;
  border-bottom: 1px dashed var(--line-soft);
}
.order-row:last-child { border-bottom: none; }
.o-type { flex-shrink: 0; width: 44px; justify-content: center; }
.o-main { flex: 1; min-width: 0; }
.o-desc {
  font-size: 13px; font-weight: 600; color: var(--ink);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.o-user { font-size: 11.5px; color: var(--muted); margin-top: 2px; }
.o-right { flex-shrink: 0; display: flex; align-items: center; gap: 8px; }
.o-amount { font-size: 13px; font-weight: 700; color: var(--muted); font-variant-numeric: tabular-nums; }
.o-amount.pay { color: #e29b2a; }

@media (max-width: 1200px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  .cols { grid-template-columns: 1fr; }
}
</style>
