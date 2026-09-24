<script setup>
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getUserPage, updateUserStatus, resetAiQuota } from '../../api'

const list = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 30, keyword: '' })

const load = async () => {
  const data = await getUserPage(query)
  list.value = data.list
  total.value = data.total
}

const toggleStatus = async (row) => {
  const target = row.status === 1 ? 0 : 1
  await updateUserStatus(row.id, target)
  ElMessage.success(target === 1 ? '已启用' : '已禁用')
  load()
}

const resetQuota = async (row) => {
  await ElMessageBox.confirm(`确定重置「${row.username}」的 AI 免费额度吗？`, '提示', { type: 'warning' })
  await resetAiQuota(row.id)
  ElMessage.success('已重置免费额度')
}

onMounted(load)
</script>

<template>
  <div>
    <el-alert type="warning" :closable="false" show-icon
      title="管理员端账号对外开放，所以这个重置额度功能已关闭" style="margin-bottom: 16px" />

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="搜索用户名" style="width: 220px" clearable @keyup.enter="query.pageNum = 1; load()" />
      <el-button type="primary" @click="query.pageNum = 1; load()">搜索</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="120" show-overflow-tooltip />
      <el-table-column prop="username" label="用户名" min-width="130">
        <template #default="{ row }">
          <div class="ucell">
            <span class="u-avatar">{{ (row.username || 'U').slice(0, 1).toUpperCase() }}</span>
            <span class="u-name">{{ row.username }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="nickname" label="昵称" min-width="120" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.role === 'admin'" type="warning" effect="dark" size="small">管理员</el-tag>
          <el-tag v-else type="info" effect="plain" size="small">普通用户</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="coinBalance" label="余额" width="100" align="right" />
      <el-table-column label="注册时间" min-width="170">
        <template #default="{ row }">{{ row.createTime?.replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '正常' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggleStatus(row)">
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
          <el-button link type="primary" disabled
            title="管理员端账号对外开放，所以这个重置额度功能已关闭"
            @click="resetQuota(row)">重置AI额度</el-button>
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
.ucell { display: flex; align-items: center; gap: 8px; }
.u-avatar {
  width: 26px; height: 26px; border-radius: 50%; flex-shrink: 0;
  background: var(--cinnabar); color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700;
}
.u-name { font-weight: 500; }
</style>
