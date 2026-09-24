<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { getAdminLogPage } from '../../api'
import { fmtTime } from '../../utils/format'

const MODULE_LABEL = {
  AUDIT: '内容审核', USER: '用户', ORDER: '订单', FEEDBACK: '反馈',
  NOVEL: '小说', AI: 'AI 额度', IMPORT: '公版书导入'
}
const ACTION_LABEL = {
  PASS: '通过', REJECT: '拒绝', STATUS: '状态变更', HANDLE: '处理',
  RESET: '重置', SAVE: '保存', DELETE: '删除', IMPORT: '导入'
}
const TARGET_LABEL = {
  NOVEL: '作品', CHAPTER: '章节', USER: '用户', FEEDBACK: '反馈', AI_QUOTA: 'AI 额度'
}

const moduleOptions = Object.entries(MODULE_LABEL).map(([value, label]) => ({ value, label }))
const actionOptions = Object.entries(ACTION_LABEL).map(([value, label]) => ({ value, label }))

const loading = ref(false)
const list = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 30,
  module: null,
  action: null,
  keyword: '',
  onlyFailed: false
})
const dateRange = ref([])

const detailVisible = ref(false)
const current = ref(null)

const params = computed(() => {
  const p = { ...query }
  if (dateRange.value && dateRange.value.length === 2) {
    p.startDate = dateRange.value[0]
    p.endDate = dateRange.value[1]
  }
  return p
})

const load = async () => {
  loading.value = true
  try {
    const res = await getAdminLogPage(params.value)
    list.value = res?.list || []
    total.value = Number(res?.total || 0)
  } finally {
    loading.value = false
  }
}

const search = () => {
  query.pageNum = 1
  load()
}

const reset = () => {
  Object.assign(query, { module: null, action: null, keyword: '', onlyFailed: false })
  dateRange.value = []
  search()
}

const onPage = (p) => {
  query.pageNum = p
  load()
}

const openDetail = (row) => {
  current.value = row
  detailVisible.value = true
}

onMounted(load)
</script>

<template>
  <div class="log-page">
    <div class="toolbar">
      <el-select v-model="query.module" placeholder="全部模块" clearable style="width: 150px" @change="search">
        <el-option v-for="m in moduleOptions" :key="m.value" :label="m.label" :value="m.value" />
      </el-select>
      <el-select v-model="query.action" placeholder="全部动作" clearable style="width: 140px" @change="search">
        <el-option v-for="a in actionOptions" :key="a.value" :label="a.label" :value="a.value" />
      </el-select>
      <el-input
        v-model="query.keyword"
        placeholder="搜索摘要 / 详情 / 操作人"
        style="width: 220px"
        clearable
        @keyup.enter="search"
        @clear="search"
      />
      <el-date-picker
        v-model="dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 240px"
        @change="search"
      />
      <el-checkbox v-model="query.onlyFailed" @change="search">只看失败</el-checkbox>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button v-if="query.module || query.action || query.keyword || query.onlyFailed || dateRange?.length" @click="reset">重置</el-button>
      <span class="count">共 {{ total }} 条</span>
    </div>

    <el-table v-loading="loading" :data="list" stripe @row-click="openDetail">
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="120">
        <template #default="{ row }">
          <span class="who">{{ row.adminName ? '@' + row.adminName : '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="模块" width="110">
        <template #default="{ row }">
          <span class="tag-plain">{{ MODULE_LABEL[row.module] || row.module }}</span>
        </template>
      </el-table-column>
      <el-table-column label="动作" width="100">
        <template #default="{ row }">{{ ACTION_LABEL[row.action] || row.action }}</template>
      </el-table-column>
      <el-table-column prop="summary" label="操作摘要" min-width="170" show-overflow-tooltip />
      <el-table-column label="目标" width="120">
        <template #default="{ row }">
          <span v-if="row.targetType || row.targetId">
            {{ TARGET_LABEL[row.targetType] || row.targetType || '—' }}
            <span v-if="row.targetId" class="mono">#{{ row.targetId }}</span>
          </span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="详情" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="detail">{{ row.detail || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.success === 1 ? 'success' : 'danger'" size="small" effect="plain">
            {{ row.success === 1 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="80">
        <template #default="{ row }">{{ row.costMs == null ? '—' : row.costMs + 'ms' }}</template>
      </el-table-column>
    </el-table>

    <div v-if="!loading && !list.length" class="empty-tip">没有符合条件的操作记录</div>

    <div class="pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNum"
        @current-change="onPage"
      />
    </div>

    <el-drawer v-model="detailVisible" title="操作详情" size="440px">
      <div v-if="current" class="rec">
        <div class="r-row"><span>时间</span><b>{{ fmtTime(current.createTime) }}</b></div>
        <div class="r-row"><span>操作人</span><b>{{ current.adminName ? '@' + current.adminName : '—' }}（ID {{ current.adminId ?? '—' }}）</b></div>
        <div class="r-row"><span>模块 / 动作</span><b>{{ MODULE_LABEL[current.module] || current.module }} · {{ ACTION_LABEL[current.action] || current.action }}</b></div>
        <div class="r-row"><span>目标</span><b>{{ TARGET_LABEL[current.targetType] || current.targetType || '—' }} {{ current.targetId ? '#' + current.targetId : '' }}</b></div>
        <div class="r-row"><span>结果</span><b :class="current.success === 1 ? 'ok' : 'bad'">{{ current.success === 1 ? '成功' : '失败' }}</b></div>
        <div class="r-row"><span>耗时</span><b>{{ current.costMs == null ? '—' : current.costMs + ' ms' }}</b></div>
        <div class="r-row"><span>来源 IP</span><b class="mono">{{ current.ip || '—' }}</b></div>
        <div class="r-block"><span>操作摘要</span><p>{{ current.summary }}</p></div>
        <div class="r-block"><span>补充详情</span><p>{{ current.detail || '—' }}</p></div>
        <div v-if="current.errorMsg" class="r-block"><span>失败原因</span><p class="bad">{{ current.errorMsg }}</p></div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin-bottom: 14px;
}
.count { font-size: 12.5px; color: var(--muted); margin-left: auto; }

.who { color: var(--ink-2); }
.tag-plain {
  font-size: 12px; color: var(--muted);
  background: var(--paper); padding: 1px 8px; border-radius: 4px;
}
.detail { color: var(--muted); }
.mono { font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace; font-size: 12px; }
.empty-tip { padding: 48px 0; text-align: center; color: var(--muted); font-size: 13px; }
.pager { display: flex; justify-content: flex-end; margin-top: 14px; }

.rec { display: flex; flex-direction: column; gap: 12px; }
.r-row { display: flex; font-size: 13px; }
.r-row span { width: 84px; color: var(--muted); flex-shrink: 0; }
.r-row b { color: var(--ink); font-weight: 500; word-break: break-all; }
.r-block span { font-size: 12.5px; color: var(--muted); }
.r-block p {
  margin-top: 6px; font-size: 13px; color: var(--ink-2); line-height: 1.8;
  background: var(--paper); border: 1px solid var(--line-soft);
  border-radius: 8px; padding: 10px 12px; word-break: break-word;
}
.ok { color: #46603f; }
.bad { color: var(--cinnabar); }
:deep(.el-table__row) { cursor: pointer; }
</style>
