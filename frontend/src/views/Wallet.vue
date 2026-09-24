<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { createRecharge, mockPayCoin, cancelOrder, getMyRechargeOrders } from '../api'
import { useUserStore } from '../store/user'
import { fmtTime } from '../utils/format'

const userStore = useUserStore()

const chargeAmount = ref(100)
const charging = ref(false)

// 充值档位（1 币 = 1 元，不含赠送：原标注的「赠 N」与后端入账金额不符，已移除）
const packages = [50, 100, 500, 1000]

// ===== 收银台：下单 -> 展示 -> 确认支付 / 取消订单 / 超时自动关闭 =====
const cashierVisible = ref(false)
const currentOrder = ref({ orderNo: '', amount: 0, reused: false })
const paying = ref(false)
const canceling = ref(false)
const remainSeconds = ref(0)
let countdownTimer = null

const fmtCountdown = (s) => {
  const m = Math.floor(s / 60)
  const sec = s % 60
  return `${m}:${String(sec).padStart(2, '0')}`
}

const stopCountdown = () => {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
}

const startCountdown = (seconds) => {
  stopCountdown()
  remainSeconds.value = Math.max(0, seconds)
  countdownTimer = setInterval(() => {
    remainSeconds.value -= 1
    if (remainSeconds.value <= 0) {
      remainSeconds.value = 0
      stopCountdown()
      cashierVisible.value = false
      ElMessage.warning('订单已超时关闭，请重新下单')
    }
  }, 1000)
}

// 第一步：点击「立即充值」创建订单（后端复用存量待支付单），弹收银台
const openCashier = async () => {
  charging.value = true
  try {
    // axios 拦截器已返回 res.data，此处直接拿订单号对象，勿再解构 data
    const vo = await createRecharge({ amount: chargeAmount.value })
    currentOrder.value = { orderNo: vo.orderNo, amount: vo.coinAmount ?? chargeAmount.value, reused: !!vo.reused }
    startCountdown(vo.remainSeconds ?? 900)
    cashierVisible.value = true
  } catch (e) { /* 已在拦截器提示 */ } finally {
    charging.value = false
  }
}

// 第二步：收银台内「确认支付」真正入账
const confirmPay = async () => {
  paying.value = true
  try {
    await mockPayCoin(currentOrder.value.orderNo)
    ElMessage.success('充值成功')
    stopCountdown()
    cashierVisible.value = false
    await userStore.fetchUserInfo()
    loadOrders(1) // 刷新记录，让这单立刻显示为已支付
  } catch (e) { /* 已在拦截器提示 */ } finally {
    paying.value = false
  }
}

// 取消订单：终结当前待支付订单，之后可重新下单
const cancelCurrentOrder = async () => {
  canceling.value = true
  try {
    await cancelOrder(currentOrder.value.orderNo)
    ElMessage.info('订单已取消')
  } catch (e) { /* 已在拦截器提示 */ } finally {
    stopCountdown()
    canceling.value = false
    cashierVisible.value = false
  }
}

// ===== 充值记录 =====
const ORDER_STATUS_TEXT = { 0: '待支付', 1: '已支付', 2: '已取消' }
const orders = ref([])
const orderTotal = ref(0)
const orderStatus = ref('')
const orderRange = ref(null)
const orderPage = ref(1)
const orderPageSize = 10

// 日期区间快捷项（含首含尾，后端按「结束日 +1 天」换算成左闭右开区间）
const dateShortcuts = [
  {
    text: '近 7 天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setDate(start.getDate() - 6)
      return [start, end]
    }
  },
  {
    text: '近 30 天',
    value: () => {
      const end = new Date()
      const start = new Date()
      start.setDate(start.getDate() - 29)
      return [start, end]
    }
  }
]

const statusText = (s) => ORDER_STATUS_TEXT[s] || '未知'

// 空态文案需区分「无记录」与「被筛选条件排除」：后者若显示「还没有充值记录」会让用户误以为记录丢失
const hasOrderFilter = computed(() => orderStatus.value !== '' || Array.isArray(orderRange.value))
const ordEmptyText = computed(() => (hasOrderFilter.value ? '没有符合条件的记录' : '还没有充值记录'))

const loadOrders = async (page = 1) => {
  orderPage.value = page
  const params = { pageNum: page, pageSize: orderPageSize }
  if (orderStatus.value !== '') params.status = orderStatus.value
  if (Array.isArray(orderRange.value) && orderRange.value.length === 2) {
    params.startDate = orderRange.value[0]
    params.endDate = orderRange.value[1]
  }
  try {
    const res = await getMyRechargeOrders(params)
    orders.value = res?.list || []
    orderTotal.value = Number(res?.total || 0)
  } catch (e) { /* 已在拦截器提示 */ }
}

onMounted(() => {
  userStore.fetchUserInfo()
  loadOrders(1)
})

onUnmounted(stopCountdown)
</script>

<template>
  <div class="wallet">
    <section class="bal-card">
      <div class="bal-label">虚拟币余额</div>
      <div class="bal-num">{{ userStore.coinBalance }}</div>
      <div class="bal-tip">用于解锁付费章节，也可一次性解锁整本</div>
    </section>

    <section class="pkg-floor">
      <h3 class="floor-title">选择充值金额</h3>
      <div class="pkg-row">
        <div
          v-for="coin in packages"
          :key="coin"
          class="pkg-card"
          :class="{ active: chargeAmount === coin }"
          @click="chargeAmount = coin"
        >
          <div class="pkg-coin">{{ coin }}</div>
          <div class="pkg-label">币</div>
        </div>
      </div>
      <el-button type="primary" size="large" class="pkg-btn" :loading="charging" @click="openCashier">
        立即充值 {{ chargeAmount }} 币
      </el-button>
      <div class="pkg-note">虚拟币不设有效期，充值后可用于本书站全部付费内容</div>
    </section>

    <section class="ord-floor">
      <div class="ord-head">
        <h3 class="floor-title">充值记录</h3>
        <div class="ord-filters">
          <el-date-picker
            v-model="orderRange"
            type="daterange"
            size="small"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            :shortcuts="dateShortcuts"
            @change="loadOrders(1)"
          />
          <el-select v-model="orderStatus" class="ord-filter" size="small" @change="loadOrders(1)">
            <el-option label="全部状态" value="" />
            <el-option label="已支付" :value="1" />
            <el-option label="待支付" :value="0" />
            <el-option label="已取消" :value="2" />
          </el-select>
        </div>
      </div>

      <div v-if="!orders.length" class="ord-empty">{{ ordEmptyText }}</div>
      <template v-else>
        <div v-for="o in orders" :key="o.orderNo" class="ord-row">
          <div class="ord-main">
            <div class="ord-coin">{{ o.coinAmount }} 币</div>
            <div class="ord-no">{{ o.orderNo }}</div>
          </div>
          <div class="ord-status" :class="`st-${o.status}`">{{ statusText(o.status) }}</div>
          <div class="ord-time">{{ fmtTime(o.payTime || o.createTime) }}</div>
        </div>
        <el-pagination
          v-if="orderTotal > orderPageSize"
          class="ord-pager"
          layout="prev, pager, next"
          small
          :total="orderTotal"
          :page-size="orderPageSize"
          :current-page="orderPage"
          @current-change="loadOrders"
        />
      </template>
    </section>

    <el-dialog v-model="cashierVisible" title="收银台" width="420px"
      :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
      <div class="cashier">
        <div v-if="currentOrder.reused" class="cashier-reused">
          <el-icon><InfoFilled /></el-icon> 您有一笔待支付的订单，已为您恢复
        </div>
        <div class="cashier-amount">
          <span class="cashier-num">{{ currentOrder.amount }}</span>
          <span class="cashier-unit">币</span>
        </div>
        <div class="cashier-row">
          <span class="cashier-label">订单号</span>
          <span class="cashier-value mono">{{ currentOrder.orderNo }}</span>
        </div>
        <div class="cashier-row">
          <span class="cashier-label">应付金额</span>
          <span class="cashier-value">¥{{ currentOrder.amount }}.00</span>
        </div>
        <div class="cashier-row">
          <span class="cashier-label">支付方式</span>
          <span class="cashier-value">在线支付</span>
        </div>
        <div class="cashier-tip">
          <el-icon><Clock /></el-icon> 剩余 {{ fmtCountdown(remainSeconds) }} 未支付，订单将自动关闭
        </div>
      </div>
      <template #footer>
        <el-button :loading="canceling" @click="cancelCurrentOrder">取消订单</el-button>
        <el-button type="primary" :loading="paying" @click="confirmPay">确认支付</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.wallet { max-width: 1000px; }

.bal-card {
  padding: 26px 28px;
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 12px;
}
.bal-label { font-size: 13px; color: var(--muted); }
.bal-num {
  font-family: var(--serif); font-size: 40px; font-weight: 600;
  color: #8a6a20; line-height: 1.15; margin-top: 6px;
}
.bal-tip { font-size: 12.5px; color: var(--muted); margin-top: 6px; }

.pkg-floor {
  margin-top: 20px; padding: 22px 24px;
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 12px;
}
.floor-title {
  font-family: var(--serif); font-size: 16px; font-weight: 600; color: var(--ink);
  margin-bottom: 18px;
}
.pkg-row { display: flex; gap: 12px; margin-bottom: 20px; }
.pkg-card {
  flex: 1; border: 1px solid var(--line); border-radius: 10px; padding: 18px 0;
  text-align: center; cursor: pointer; transition: all .18s;
}
.pkg-card:hover { border-color: var(--cinnabar); }
.pkg-card.active { border-color: var(--cinnabar); background: var(--cinnabar-bg); }
.pkg-coin { font-family: var(--serif); font-size: 24px; font-weight: 600; color: #8a6a20; }
.pkg-label { font-size: 12px; color: var(--muted); margin-top: 2px; }
.pkg-btn { width: 100%; }
.pkg-note { font-size: 12px; color: var(--muted); margin-top: 12px; text-align: center; }

.ord-floor {
  margin-top: 20px; padding: 22px 24px;
  background: var(--paper-2); border: 1px solid var(--line); border-radius: 12px;
}
.ord-head { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; flex-wrap: wrap; }
.ord-head .floor-title { margin-bottom: 0; }
.ord-filters { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.ord-filter { width: 132px; }
.ord-empty { color: var(--muted); font-size: 13px; padding: 26px 0; text-align: center; }
.ord-row {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 4px; border-bottom: 1px solid var(--line-soft);
}
.ord-row:last-child { border-bottom: none; }
.ord-main { flex: 1; min-width: 0; }
.ord-coin { font-family: var(--serif); font-size: 15px; font-weight: 600; color: var(--ink); }
.ord-no {
  font-size: 12px; color: var(--muted); margin-top: 3px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ord-status { font-size: 12.5px; flex-shrink: 0; color: var(--muted); }
.ord-status.st-0 { color: #8a6a20; }
.ord-status.st-1 { color: #3B6D11; }
.ord-time { font-size: 12px; color: var(--muted); flex-shrink: 0; width: 96px; text-align: right; }
.ord-pager { margin-top: 16px; justify-content: flex-end; }

.cashier { display: flex; flex-direction: column; gap: 14px; padding: 4px 0; }
.cashier-amount { text-align: center; padding: 8px 0 2px; }
.cashier-num { font-family: var(--serif); font-size: 38px; font-weight: 600; color: #8a6a20; }
.cashier-unit { font-size: 14px; color: var(--muted); margin-left: 4px; }
.cashier-row { display: flex; align-items: center; gap: 12px; font-size: 13px; }
.cashier-label { width: 64px; color: var(--muted); flex-shrink: 0; }
.cashier-value { color: var(--ink); word-break: break-all; }
.cashier-value.mono { font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace; font-size: 12px; }
.cashier-tip {
  font-size: 12px; color: #8a6a20; background: #fbf3e2;
  border-radius: 8px; padding: 8px 12px;
  display: flex; align-items: center; gap: 6px;
}
.cashier-reused {
  font-size: 12px; color: var(--cinnabar); background: var(--cinnabar-bg);
  border-radius: 8px; padding: 8px 12px;
  display: flex; align-items: center; gap: 6px;
}

@media (max-width: 900px) {
  .pkg-row { flex-wrap: wrap; }
  .pkg-card { flex: 1 1 40%; }
}

/* 窄屏（手机）：充值记录的筛选区（日期范围 + 下拉）宽度为 492px，
   而 .ord-filters 为 flex item、min-width:auto 时不收缩，会将整页撑宽 141px。
   改为独占一行并允许内部换行。 */
@media (max-width: 768px) {
  .ord-filters { margin-left: 0; width: 100%; flex-wrap: wrap; min-width: 0; }
  .ord-filter { width: auto; flex: 1 1 120px; }
}
</style>
