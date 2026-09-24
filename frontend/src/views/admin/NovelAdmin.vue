<script setup>
import { ref, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAdminNovelPage, getCategories, saveNovel, deleteNovel, changeNovelStatus, uploadCover
} from '../../api'

const list = ref([])
const total = ref(0)
const categories = ref([])
const query = reactive({ pageNum: 1, pageSize: 30, keyword: '', categoryId: null })
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ id: null, title: '', categoryId: null, intro: '', tags: '', coverUrl: '', author: '', coinPrice: 10, totalChapters: 20 })

const load = async () => {
  // 使用管理端专用接口：可查看未过审 / 已下架的作品（对外书库接口不具备该能力，也不应具备）
  const data = await getAdminNovelPage(query)
  list.value = data.list
  total.value = data.total
}

const openDialog = (row) => {
  if (row) {
    Object.assign(form, {
      id: row.id, title: row.title, categoryId: row.categoryId,
      intro: row.intro, tags: row.tags, coverUrl: row.coverUrl || '', author: row.author || '',
      coinPrice: row.coinPrice, totalChapters: row.totalChapters
    })
  } else {
    Object.assign(form, { id: null, title: '', categoryId: null, intro: '', tags: '', coverUrl: '', author: '', coinPrice: 10, totalChapters: 20 })
  }
  dialogVisible.value = true
}

const uploading = ref(false)

// 封面仅支持「上传图片」，不提供图片地址输入；内部仍以 URL 存储，但不暴露于界面
const onCoverChange = async (uploadFile) => {
  const file = uploadFile?.raw
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    form.coverUrl = await uploadCover(fd)
    ElMessage.success('封面上传成功')
  } catch (e) { /* 已在拦截器提示 */ } finally {
    uploading.value = false
  }
}

const save = async () => {
  if (!form.title) {
    ElMessage.warning('请输入书名')
    return
  }
  saving.value = true
  try {
    await saveNovel(form)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

const toggleStatus = async (row) => {
  const target = row.status === 1 ? 0 : 1
  await changeNovelStatus(row.id, target)
  ElMessage.success(target === 1 ? '已上架' : '已下架')
  load()
}

const remove = async (row) => {
  await ElMessageBox.confirm(`确定删除《${row.title}》吗？`, '提示', { type: 'warning' })
  await deleteNovel(row.id)
  ElMessage.success('删除成功')
  load()
}

const resetQuery = () => {
  query.keyword = ''
  query.categoryId = null
  query.pageNum = 1
  load()
}

onMounted(async () => {
  categories.value = await getCategories()
  load()
})
</script>

<template>
  <div>
    <div class="page-head">
      <div class="head-btns">
        <el-button type="primary" @click="openDialog(null)">新增小说</el-button>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="搜索书名" style="width: 200px" clearable @keyup.enter="query.pageNum = 1; load()" />
      <el-select v-model="query.categoryId" placeholder="全部分类" clearable style="width: 150px"
        @change="query.pageNum = 1; load()">
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-button type="primary" @click="query.pageNum = 1; load()">搜索</el-button>
      <el-button v-if="query.keyword || query.categoryId" @click="resetQuery">重置</el-button>
    </div>

    <el-table :data="list" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="title" label="书名" min-width="170">
        <template #default="{ row }">
          <div class="tcell">
            <span class="tc-cover">{{ row.title.slice(0, 1) }}</span>
            <span class="tc-title">{{ row.title }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="categoryName" label="分类" width="100" />
      <el-table-column prop="author" label="作者" width="100" />
      <el-table-column prop="readCount" label="阅读量" width="100" sortable :sort-method="(a, b) => Number(a.readCount) - Number(b.readCount)" />
      <el-table-column prop="likeCount" label="点赞" width="90" />
      <el-table-column prop="totalChapters" label="总章节" width="90" />
      <el-table-column prop="coinPrice" label="整本价" width="90" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '上架' : '下架' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
            {{ row.status === 1 ? '下架' : '上架' }}
          </el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination background layout="prev, pager, next, total" :total="total"
        :page-size="query.pageSize" :current-page="query.pageNum"
        @current-change="(p) => { query.pageNum = p; load() }" />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑小说' : '新增小说'" width="560px">
      <el-form label-width="90px">
        <el-form-item label="书名" required>
          <el-input v-model="form.title" placeholder="请输入书名" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" placeholder="选择分类" clearable style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="封面">
          <div class="cover-row">
            <div class="cover-thumb">
              <img v-if="form.coverUrl" :src="form.coverUrl" alt="封面" />
              <span v-else class="thumb-ph">暂无</span>
            </div>
            <div class="cover-ops">
              <el-upload
                action="#"
                accept="image/*"
                :auto-upload="false"
                :show-file-list="false"
                :on-change="onCoverChange"
              >
                <el-button :loading="uploading"><el-icon><Upload /></el-icon><span>上传图片</span></el-button>
              </el-upload>
              <el-button v-if="form.coverUrl" link type="danger" size="small" @click="form.coverUrl = ''">移除封面</el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="逗号分隔，如：重生,逆袭" />
        </el-form-item>
        <el-form-item label="作者">
          <el-input v-model="form.author" placeholder="作者名" />
        </el-form-item>
        <el-form-item label="整本价">
          <el-input-number v-model="form.coinPrice" :min="0" />
        </el-form-item>
        <el-form-item label="总章节">
          <el-input-number v-model="form.totalChapters" :min="1" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.intro" type="textarea" :rows="3" placeholder="内容简介" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

  </div>
</template>

<style scoped>
.page-head { display: flex; justify-content: flex-end; margin-bottom: 14px; }
.head-btns { display: flex; gap: 10px; }
.toolbar { display: flex; gap: 10px; margin-bottom: 16px; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
.tcell { display: flex; align-items: center; gap: 8px; }
.tc-cover {
  width: 26px; height: 26px; border-radius: 6px; flex-shrink: 0;
  background: var(--cinnabar); color: #fff;
  display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700;
}
.tc-title { font-weight: 500; }
.cover-row { display: flex; gap: 16px; align-items: flex-start; width: 100%; }
.cover-thumb {
  width: 84px; height: 112px; border-radius: 6px; overflow: hidden; flex-shrink: 0;
  border: 1px solid var(--line); background: var(--paper);
  display: flex; align-items: center; justify-content: center;
}
.cover-thumb img { width: 100%; height: 100%; object-fit: cover; }
.thumb-ph { font-size: 12px; color: var(--muted); }
.cover-ops { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.cover-ops .el-upload { display: block; }
</style>
