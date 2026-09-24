<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminCategories, createCategory, updateCategory, deleteCategory } from '../../api'

const list = ref([])
const loading = ref(false)
const saving = ref(false)

const dialog = reactive({ visible: false, id: null, novelCount: 0, form: { name: '', sort: 1, status: 1 } })

const load = async () => {
  loading.value = true
  try {
    list.value = (await getAdminCategories()) || []
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

const openCreate = () => {
  const maxSort = list.value.reduce((m, c) => Math.max(m, Number(c.sort) || 0), 0)
  dialog.id = null
  dialog.novelCount = 0
  dialog.form = { name: '', sort: maxSort + 1, status: 1 }
  dialog.visible = true
}

const openEdit = (row) => {
  dialog.id = row.id
  dialog.novelCount = Number(row.novelCount) || 0
  dialog.form = { name: row.name, sort: Number(row.sort) || 0, status: row.status }
  dialog.visible = true
}

const submit = async () => {
  const name = (dialog.form.name || '').trim()
  if (!name) return ElMessage.warning('请填写分类名称')
  if (dialog.form.sort === null || dialog.form.sort === undefined) return ElMessage.warning('请填写排序值')
  // 重名在前端先拦截一次，可节省一次请求往返；后端仍会再判断一次（并发场景下前端无法拦截）
  if (list.value.some((c) => c.name === name && c.id !== dialog.id)) {
    return ElMessage.warning(`分类「${name}」已存在`)
  }
  saving.value = true
  try {
    const payload = { name, sort: dialog.form.sort, status: dialog.form.status }
    if (dialog.id) {
      await updateCategory(dialog.id, payload)
    } else {
      await createCategory(payload)
    }
    ElMessage.success(dialog.id ? '已保存' : '已新增分类')
    dialog.visible = false
    load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    saving.value = false
  }
}

const toggleStatus = async (row) => {
  const next = row.status === 1 ? 0 : 1
  if (next === 0) {
    const count = Number(row.novelCount) || 0
    const extra = count > 0
      ? `它下面的 ${count} 部作品也会从分类导航里消失（作品本身仍可搜索和阅读）。`
      : ''
    try {
      await ElMessageBox.confirm(
        `禁用后「${row.name}」不再出现在前台的分类导航里。${extra}随时可以再启用。`,
        '禁用分类',
        { type: 'warning', confirmButtonText: '确认禁用', cancelButtonText: '取消' }
      )
    } catch (e) {
      return
    }
  }
  try {
    await updateCategory(row.id, { name: row.name, sort: row.sort, status: next })
    ElMessage.success(next === 1 ? '已启用' : '已禁用')
    load()
  } catch (e) { /* 拦截器已提示 */ }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      `删除后「${row.name}」从分类列表与前台导航中移除。这个分类下没有作品，删除不影响任何一本书。`,
      '删除分类',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  try {
    await deleteCategory(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) { /* 拦截器已提示 */ }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>分类管理</h2>
      <span class="sub">分类决定前台导航顺序与书封配色，改动会立即影响整站</span>
    </div>

    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增分类</el-button>
      <span class="count">{{ list.length }} 个分类</span>
    </div>

    <el-table :data="list" v-loading="loading" stripe>
      <el-table-column label="分类名称" min-width="180">
        <template #default="{ row }">
          <span class="cat-name">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="作品数" width="100" align="center">
        <template #default="{ row }">
          <span :class="{ zero: !Number(row.novelCount) }">{{ row.novelCount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="排序" width="90" align="center" prop="sort" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small" effect="light" round>
            {{ row.status === 1 ? '启用' : '已禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link size="small" @click="toggleStatus(row)">
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
          <el-tooltip v-if="Number(row.novelCount) > 0" placement="top"
                      content="该分类下还有作品，需先把它们改到别的分类">
            <span class="del-wrap">
              <el-button link type="danger" size="small" disabled>删除</el-button>
            </span>
          </el-tooltip>
          <el-button v-else link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑分类' : '新增分类'" width="480px">
      <el-form label-width="82px" @submit.prevent>
        <el-form-item label="分类名称" required>
          <el-input v-model="dialog.form.name" maxlength="50" show-word-limit
                    placeholder="如：志怪神魔" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="dialog.form.sort" :min="0" :max="9999" controls-position="right" />
          <span class="hint">越小越靠前</span>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="dialog.form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <div v-if="dialog.id && dialog.novelCount > 0" class="warn-block">
        该分类下有 {{ dialog.novelCount }} 部作品。禁用后它们不再出现在分类导航里（作品本身仍可搜索和阅读）；
        删除会被拒绝，需要先把作品改到别的分类。
      </div>
      <div v-else-if="!dialog.id" class="note-block">
        新分类会立即出现在前台导航里。分类的封面配色由系统自动分配。
      </div>

      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
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
.cat-name { font-weight: 500; }
.zero { color: var(--muted); }
.del-wrap { display: inline-block; }
.hint { color: var(--muted); font-size: 12.5px; margin-left: 10px; }
.warn-block {
  background: var(--cinnabar-bg); color: #8a4b45;
  font-size: 12.5px; line-height: 1.75;
  padding: 10px 12px; border-radius: 8px;
}
.note-block {
  background: var(--paper-2); color: var(--muted);
  font-size: 12.5px; line-height: 1.75;
  padding: 10px 12px; border-radius: 8px;
}
</style>
