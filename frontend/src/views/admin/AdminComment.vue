<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCommentAdminPage, deleteCommentAdmin } from '../../api'
import { fmtTime } from '../../utils/format'

const list = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 30, keyword: '', type: null })

/**
 * 回复与章评共用 chapterId 字段，展示时以「回复」优先：
 * 管理员处置违规内容时更关心它挂在谁的评论下面。
 */
const typeText = (row) => (row.parentId ? '回复' : row.chapterId ? '章评' : '书评')
const typeTag = (row) => (row.parentId ? 'info' : row.chapterId ? 'warning' : undefined)

const load = async () => {
  const params = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.keyword.trim()) params.keyword = query.keyword.trim()
  if (query.type === 0 || query.type === 1) params.type = query.type
  const data = await getCommentAdminPage(params)
  list.value = data.list || []
  total.value = Number(data.total || 0)
}

const search = () => { query.pageNum = 1; load() }

const remove = async (row) => {
  const isReply = !!row.parentId
  try {
    await ElMessageBox.confirm(
      isReply
        ? '确认删除这条回复？删除后无法恢复。'
        : '确认删除这条评论？其下所有回复会一并删除，且无法恢复。',
      '删除评论',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return // 用户取消
  }
  await deleteCommentAdmin(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="搜索评论内容" style="width: 240px"
        clearable @keyup.enter="search" @clear="search" />
      <el-select v-model="query.type" placeholder="类型" style="width: 120px" clearable @change="search">
        <el-option label="书评" :value="0" />
        <el-option label="章评" :value="1" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="typeTag(row)">{{ typeText(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
      <el-table-column label="评论人" width="130">
        <template #default="{ row }">{{ row.authorName || '读者' }}</template>
      </el-table-column>
      <el-table-column label="所属作品" min-width="170" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="{ gone: !row.novelTitle }">{{ row.novelTitle || '作品已删除' }}</span>
          <span v-if="row.chapterNo" class="chapter">第 {{ row.chapterNo }} 章</span>
        </template>
      </el-table-column>
      <el-table-column prop="likeCount" label="点赞" width="70" align="right" />
      <el-table-column label="发布时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination background layout="prev, pager, next, total" :total="total"
        :page-size="query.pageSize" :current-page="query.pageNum"
        @current-change="(p) => { query.pageNum = p; load() }" />
    </div>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: 16px; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.chapter { margin-left: 8px; font-size: 12px; color: var(--muted); }
.gone { color: var(--muted); }
</style>
