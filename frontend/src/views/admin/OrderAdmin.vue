<script setup>
import { ref, onMounted, reactive } from 'vue'
import { getRechargeOrderPage, getSubscribeOrderPage } from '../../api'
import { fmtTime } from '../../utils/format'

const activeTab = ref('recharge')
const rechargeList = ref([])
const rechargeTotal = ref(0)
const subscribeList = ref([])
const subscribeTotal = ref(0)
const rechargeQuery = reactive({ pageNum: 1, pageSize: 30, status: null, keyword: '' })
const subscribeQuery = reactive({ pageNum: 1, pageSize: 30, status: null, keyword: '' })
const filter = reactive({ status: null, keyword: '' })

const statusOptions = [
  { value: null, label: '全部状态' },
  { value: 0, label: '待支付' },
  { value: 1, label: '已支付' },
  { value: 2, label: '已取消' }
]

const statusTag = (s) => (s === 1 ? 'success' : s === 0 ? 'warning' : 'info')
const statusText = (s) => (s === 1 ? '已支付' : s === 0 ? '待支付' : '已取消')

const syncQuery = () => {
  const q = activeTab.value === 'recharge' ? rechargeQuery : subscribeQuery
  q.status = filter.status
  q.keyword = filter.keyword
  q.pageNum = 1
}

const loadRecharge = async () => {
  const data = await getRechargeOrderPage(rechargeQuery)
  rechargeList.value = data.list
  rechargeTotal.value = data.total
}
const loadSubscribe = async () => {
  const data = await getSubscribeOrderPage(subscribeQuery)
  subscribeList.value = data.list
  subscribeTotal.value = data.total
}

const load = () => {
  syncQuery()
  if (activeTab.value === 'recharge') loadRecharge()
  else loadSubscribe()
}

const onTab = (name) => {
  filter.keyword = ''
  filter.status = null
  if (name === 'recharge') {
    rechargeQuery.pageNum = 1
    loadRecharge()
  } else {
    subscribeQuery.pageNum = 1
    loadSubscribe()
  }
}

const fmtUser = (row) => row.username || `用户#${row.userId}`
const fmtMoney = (n) => Number(n ?? 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })

onMounted(() => {
  loadRecharge()
  loadSubscribe()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <el-input v-model="filter.keyword" placeholder="搜索订单号 / 用户名" style="width: 220px" clearable
        @keyup.enter="load()" />
      <el-select v-model="filter.status" placeholder="全部状态" style="width: 130px" @change="load()">
        <el-option v-for="s in statusOptions" :key="String(s.value)" :label="s.label" :value="s.value" />
      </el-select>
      <el-button type="primary" @click="load()">查询</el-button>
    </div>

    <el-tabs v-model="activeTab" @tab-change="onTab">
      <el-tab-pane label="充值订单" name="recharge">
        <el-table :data="rechargeList" border stripe>
          <el-table-column prop="orderNo" label="订单号" min-width="200" show-overflow-tooltip />
          <el-table-column label="用户" min-width="110">
            <template #default="{ row }">{{ fmtUser(row) }}</template>
          </el-table-column>
          <el-table-column prop="coinAmount" label="充值币数" width="100" align="right" />
          <el-table-column label="支付金额(元)" width="120" align="right">
            <template #default="{ row }">{{ fmtMoney(row.payAmount) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" min-width="165">
            <template #default="{ row }">{{ fmtTime(row.createTime, 19) }}</template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination background layout="prev, pager, next, total" :total="rechargeTotal"
            :page-size="rechargeQuery.pageSize" :current-page="rechargeQuery.pageNum"
            @current-change="(p) => { rechargeQuery.pageNum = p; loadRecharge() }" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="解锁订单" name="subscribe">
        <el-table :data="subscribeList" border stripe>
          <el-table-column prop="orderNo" label="订单号" min-width="200" show-overflow-tooltip />
          <el-table-column label="用户" min-width="110">
            <template #default="{ row }">{{ fmtUser(row) }}</template>
          </el-table-column>
          <el-table-column prop="novelId" label="小说ID" width="100" />
          <el-table-column label="章节ID" width="100">
            <template #default="{ row }">
              <span v-if="row.chapterId">{{ row.chapterId }}</span>
              <span v-else class="whole-book">整本</span>
            </template>
          </el-table-column>
          <el-table-column prop="coinAmount" label="消耗币数" width="100" align="right" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" min-width="165">
            <template #default="{ row }">{{ fmtTime(row.createTime, 19) }}</template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination background layout="prev, pager, next, total" :total="subscribeTotal"
            :page-size="subscribeQuery.pageSize" :current-page="subscribeQuery.pageNum"
            @current-change="(p) => { subscribeQuery.pageNum = p; loadSubscribe() }" />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.whole-book { color: #b23a2e; font-size: 12.5px; }
</style>
