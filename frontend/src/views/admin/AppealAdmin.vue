<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAppealPage, handleAppeal } from '../../api'
import { fmtTime } from '../../utils/format'

const list = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ pageNum: 1, pageSize: 20, status: 0 })

const statusMap = {
  0: { type: 'warning', text: '待处理' },
  1: { type: 'success', text: '已通过' },
  2: { type: 'info', text: '已驳回' }
}
const statusOf = (s) => statusMap[s] || { type: 'info', text: '-' }

const load = async () => {
  loading.value = true
  try {
    const params = { pageNum: query.pageNum, pageSize: query.pageSize }
    if (query.status !== null && query.status !== '') params.status = query.status
    const data = await getAppealPage(params)
    list.value = data.list || []
    total.value = Number(data.total || 0)
  } finally {
    loading.value = false
  }
}

const onStatus = (v) => {
  query.status = v
  query.pageNum = 1
  load()
}
const onPage = (p) => {
  query.pageNum = p
  load()
}

const dialog = reactive({ visible: false, id: null, novelTitle: '', novelAuthor: '', nickname: '', reason: '', reply: '' })
const saving = ref(false)

const openHandle = (row) => {
  dialog.id = row.id
  dialog.novelTitle = row.novelTitle
  dialog.novelAuthor = row.novelAuthor
  dialog.nickname = row.userNickname
  dialog.reason = row.reason
  dialog.reply = ''
  dialog.visible = true
}

const submit = async (approved) => {
  if (!approved) {
    try {
      await ElMessageBox.confirm(
        '驳回后作品的「已完结」状态保持不变，作者会收到站内信。建议在处理意见里写明原因。',
        '驳回申请',
        { type: 'warning', confirmButtonText: '确认驳回', cancelButtonText: '取消' }
      )
    } catch (e) {
      return
    }
  } else {
    try {
      await ElMessageBox.confirm(
        `通过后《${dialog.novelTitle}》将恢复为「连载中」，作者可以继续更新章节。`,
        '通过申请',
        { type: 'info', confirmButtonText: '确认通过', cancelButtonText: '取消' }
      )
    } catch (e) {
      return
    }
  }
  saving.value = true
  try {
    await handleAppeal(dialog.id, { approved, reply: dialog.reply.trim() || undefined })
    ElMessage.success(approved ? '已通过，作品已恢复连载' : '已驳回')
    dialog.visible = false
    load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>作品申请</h2>
      <span class="sub">作者提交的业务申请（当前为「解除完结」），通过后会直接改变作品状态</span>
    </div>

    <div class="toolbar">
      <el-radio-group :model-value="query.status" @change="onStatus">
        <el-radio-button :value="0">待处理</el-radio-button>
        <el-radio-button :value="1">已通过</el-radio-button>
        <el-radio-button :value="2">已驳回</el-radio-button>
        <el-radio-button :value="''">全部</el-radio-button>
      </el-radio-group>
      <span class="count">{{ total }} 条</span>
    </div>

    <el-table :data="list" v-loading="loading" stripe>
      <el-table-column label="作品" min-width="200">
        <template #default="{ row }">
          <div class="tc-title">{{ row.novelTitle || '-' }}</div>
          <div class="tc-sub">{{ row.novelAuthor || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="申请人" width="140">
        <template #default="{ row }">{{ row.userNickname || '-' }}</template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ row.typeText }}</template>
      </el-table-column>
      <el-table-column label="申请理由" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="reason">{{ row.reason || '（未填写）' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusOf(row.status).type" size="small" effect="light" round>
            {{ statusOf(row.status).text }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="处理结果" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.status === 0" class="muted">-</span>
          <span v-else>
            {{ row.adminReply || '（无处理意见）' }}
            <span class="muted"> · {{ fmtTime(row.handleTime, 10) }}</span>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" link type="primary" size="small" @click="openHandle(row)">
            处理
          </el-button>
          <span v-else class="muted">已处理</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager" v-if="total > query.pageSize">
      <el-pagination
        layout="prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNum"
        @current-change="onPage"
      />
    </div>

    <el-dialog v-model="dialog.visible" title="处理申请" width="560px">
      <div class="d-block">
        <div class="d-label">作品</div>
        <div class="d-value">《{{ dialog.novelTitle }}》 · {{ dialog.novelAuthor }}</div>
      </div>
      <div class="d-block">
        <div class="d-label">申请人</div>
        <div class="d-value">{{ dialog.nickname }}</div>
      </div>
      <div class="d-block">
        <div class="d-label">申请理由</div>
        <div class="d-value">{{ dialog.reason || '（未填写）' }}</div>
      </div>
      <div class="d-block">
        <div class="d-label">处理意见</div>
        <el-input
          v-model="dialog.reply"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="会通过站内信发给作者，驳回时建议写明原因"
        />
      </div>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button :loading="saving" @click="submit(false)">驳回</el-button>
        <el-button type="primary" :loading="saving" @click="submit(true)">通过并恢复连载</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 16px; }
.page-head h2 { font-size: 18px; margin: 0; }
.sub { color: var(--muted); font-size: 12.5px; }
.toolbar { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
.count { color: var(--muted); font-size: 12.5px; }
.tc-title { font-weight: 500; }
.tc-sub { color: var(--muted); font-size: 12.5px; margin-top: 2px; }
.reason { color: var(--ink); }
.muted { color: var(--muted); font-size: 12.5px; }
.pager { display: flex; justify-content: center; margin-top: 18px; }
.d-block { margin-bottom: 14px; }
.d-label { font-size: 12.5px; color: var(--muted); margin-bottom: 6px; }
.d-value { font-size: 13.5px; line-height: 1.7; }
</style>
