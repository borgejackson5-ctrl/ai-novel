<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { getFeedbackPage, handleFeedback } from '../../api'
import { fmtTime } from '../../utils/format'

const list = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 30, status: null, type: '' })

const typeMap = { FEELING: '使用感受', SUGGESTION: '功能建议', BUG: 'Bug 反馈' }
const statusMap = {
  0: { type: 'info', text: '待处理' },
  1: { type: 'success', text: '已采纳' },
  2: { type: 'warning', text: '未采纳' }
}

const load = async () => {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.status !== null && query.status !== '') params.status = query.status
  if (query.type) params.type = query.type
  const data = await getFeedbackPage(params)
  list.value = data.list
  total.value = data.total
}

const search = () => { query.pageNum = 1; load() }

// ===== 处理弹窗 =====
const dialog = reactive({ visible: false, id: null, content: '', type: '', user: '', contact: '', status: 1, rewardCoin: 0, reply: '' })
const saving = ref(false)

const openHandle = (row) => {
  dialog.id = row.id
  dialog.content = row.content
  dialog.type = typeMap[row.type] || row.type
  dialog.user = row.anonymous === 1 ? '匿名用户' : (row.nickname || row.username || '未知用户')
  dialog.contact = row.contact || ''
  dialog.status = 1
  dialog.rewardCoin = 0
  dialog.reply = ''
  dialog.visible = true
}

const isAdopted = computed(() => dialog.status === 1)

const confirmHandle = async () => {
  if (dialog.rewardCoin < 0 || dialog.rewardCoin > 10000) {
    ElMessage.warning('奖励币数需在 0~10000 之间')
    return
  }
  saving.value = true
  try {
    await handleFeedback(dialog.id, {
      status: dialog.status,
      reply: dialog.reply.trim() || undefined,
      rewardCoin: dialog.status === 1 ? dialog.rewardCoin : 0
    })
    ElMessage.success('已处理')
    dialog.visible = false
    load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-select v-model="query.status" placeholder="处理状态" style="width: 140px" clearable @change="search">
        <el-option label="待处理" :value="0" />
        <el-option label="已采纳" :value="1" />
        <el-option label="未采纳" :value="2" />
      </el-select>
      <el-select v-model="query.type" placeholder="反馈类型" style="width: 140px" clearable @change="search">
        <el-option label="使用感受" value="FEELING" />
        <el-option label="功能建议" value="SUGGESTION" />
        <el-option label="Bug 反馈" value="BUG" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="100" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ typeMap[row.type] || row.type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="content" label="内容" min-width="220" show-overflow-tooltip />
      <el-table-column label="用户" width="120">
        <template #default="{ row }">
          <span v-if="row.anonymous === 1" class="anon">匿名用户</span>
          <span v-else>{{ row.nickname || row.username }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="statusMap[row.status]?.type">{{ statusMap[row.status]?.text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rewardCoin" label="奖励" width="80" align="right">
        <template #default="{ row }">{{ row.rewardCoin > 0 ? row.rewardCoin : '-' }}</template>
      </el-table-column>
      <el-table-column label="提交时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 0" link type="primary" @click="openHandle(row)">处理</el-button>
          <el-button v-else link type="info" @click="openHandle(row)">查看</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination background layout="prev, pager, next, total" :total="total"
        :page-size="query.pageSize" :current-page="query.pageNum"
        @current-change="(p) => { query.pageNum = p; load() }" />
    </div>

    <el-dialog v-model="dialog.visible" title="处理反馈" width="560px">
      <div class="d-head">
        <el-tag size="small" effect="plain">{{ dialog.type }}</el-tag>
        <span class="d-user">{{ dialog.user }}</span>
        <span v-if="dialog.contact" class="d-contact"><el-icon><Message /></el-icon>{{ dialog.contact }}</span>
      </div>
      <div class="d-content">{{ dialog.content }}</div>

      <el-form label-position="top" style="margin-top: 14px">
        <el-form-item label="处理结果">
          <el-radio-group v-model="dialog.status">
            <el-radio :value="1">采纳</el-radio>
            <el-radio :value="2">未采纳</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="isAdopted" label="奖励虚拟币数">
          <el-input-number v-model="dialog.rewardCoin" :min="0" :max="10000" />
          <div class="hint">采纳 Bug 或合理建议可发放奖励（0 表示不奖励）</div>
        </el-form-item>
        <el-form-item label="回复（用户可见）">
          <el-input v-model="dialog.reply" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="感谢反馈 / 采纳说明 / 未采纳原因…" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirmHandle">确认处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: 16px; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.anon { color: #b0b3b8; }
.d-head { display: flex; align-items: center; gap: 8px; }
.d-user { font-weight: 600; }
.d-contact { color: var(--muted); font-size: 13px; margin-left: auto; }
.d-content { margin-top: 12px; padding: 12px; background: #f7f7fb; border-radius: 8px; line-height: 1.7; white-space: pre-wrap; }
.hint { color: var(--muted); font-size: 12px; width: 100%; margin-top: 4px; }
</style>
