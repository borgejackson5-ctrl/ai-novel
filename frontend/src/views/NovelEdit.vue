<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getMyNovelEdit, submitNovelEdit, getCategories, generateCover, uploadCover } from '../api'
import WorkbenchTabs from '../components/WorkbenchTabs.vue'

const route = useRoute()
const router = useRouter()

const novelId = computed(() => route.params.id)
const loading = ref(true)
const saving = ref(false)
const categories = ref([])
const origin = ref(null)
const coverGenerating = ref(false)
const uploading = ref(false)

const form = ref({ title: '', categoryId: null, author: '', intro: '', tags: '', coverUrl: '' })

// 变更待审：本次改动还在审核，前台显示的仍是下面这组旧值
const pending = computed(() => origin.value?.auditStatus === 3)
const rejected = computed(() => origin.value?.auditStatus === 2)
// 已完结的作品：只剩书名和封面可改，其余字段置灰（服务端也会拦，这里只是提示）
const finished = computed(() => origin.value?.serialStatus === 1)

// 逐项列出「当前 → 修改后」，让作者确认自己改了什么
const diffRows = computed(() => {
  const o = origin.value
  if (!o || !pending.value) return []
  const cmp = [
    ['书名', o.title, o.pendingTitle],
    ['分类', o.categoryName, categoryNameOf(o.pendingCategoryId)],
    ['笔名', o.author, o.pendingAuthor],
    ['简介', o.intro, o.pendingIntro],
    ['标签', o.tags, o.pendingTags]
  ]
  return cmp.filter(([, a, b]) => (a || '') !== (b || ''))
})

// 封面单独处理：对照区展示两张缩略图，不以文本形式展示图片地址
const coverChanged = computed(() => {
  const o = origin.value
  return !!o && pending.value && (o.coverUrl || '') !== (o.pendingCoverUrl || '')
})

const categoryNameOf = (id) => categories.value.find((c) => c.id === id)?.name || '—'

const load = async () => {
  loading.value = true
  try {
    const data = await getMyNovelEdit(novelId.value)
    origin.value = data
    form.value = {
      title: data.title || '',
      categoryId: data.categoryId ?? null,
      author: data.author || '',
      intro: data.intro || '',
      tags: data.tags || '',
      coverUrl: data.coverUrl || ''
    }
  } catch (e) { /* 已在拦截器提示 */ } finally {
    loading.value = false
  }
}

const generateCoverImg = async () => {
  const seed = `${(form.value.title || '').trim()} ${(form.value.tags || '').trim()}`.trim()
  if (!seed) {
    ElMessage.warning('请先填写书名或标签，便于生成封面')
    return
  }
  coverGenerating.value = true
  try {
    form.value.coverUrl = await generateCover(seed)
    ElMessage.success('封面生成成功')
  } catch (e) { /* 已在拦截器提示 */ } finally {
    coverGenerating.value = false
  }
}

const onCoverChange = async (uploadFile) => {
  const file = uploadFile?.raw
  if (!file) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', file)
    form.value.coverUrl = await uploadCover(fd)
    ElMessage.success('封面上传成功')
  } catch (e) { /* 已在拦截器提示 */ } finally {
    uploading.value = false
  }
}

const save = async () => {
  if (!form.value.title.trim()) return ElMessage.warning('请填写书名')
  if (!form.value.categoryId) return ElMessage.warning('请选择分类')

  try {
    await ElMessageBox.confirm(
      '提交后需要审核，审核期间作品页仍展示原内容；通过后自动生效。',
      '提交作品信息修改',
      { confirmButtonText: '提交审核', cancelButtonText: '再改改', type: 'warning' }
    )
  } catch (e) {
    return
  }

  saving.value = true
  try {
    await submitNovelEdit(novelId.value, {
      title: form.value.title.trim(),
      categoryId: form.value.categoryId,
      author: form.value.author.trim(),
      intro: form.value.intro,
      tags: form.value.tags,
      coverUrl: form.value.coverUrl
    })
    ElMessage.success('已提交，等待审核')
    await load()
  } catch (e) { /* 已在拦截器提示 */ } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    categories.value = (await getCategories()) || []
  } catch (e) { /* 忽略 */ }
  await load()
})
</script>

<template>
  <div class="edit-page" v-loading="loading">
    <WorkbenchTabs :novel-id="novelId" active="edit" />
    <div class="page-head">
      <h2 class="section-title">编辑作品信息</h2>
    </div>

    <el-alert
      v-if="pending"
      type="warning"
      :closable="false"
      show-icon
      title="有修改正在审核中"
      description="作品页此刻展示的仍是原内容，审核通过后自动生效；重新提交会覆盖上一次的修改。"
      style="margin-bottom: 16px"
    />
    <el-alert
      v-else-if="rejected"
      type="error"
      :closable="false"
      show-icon
      title="作品未通过审核"
      :description="origin?.auditResult || '请修改后重新提交'"
      style="margin-bottom: 16px"
    />

    <el-alert
      v-if="finished"
      type="warning"
      :closable="false"
      show-icon
      title="作品已完结"
      description="完结作品只能修改书名和封面。如需修改简介、标签、分类或继续更新章节，请先到「我的作品」申请恢复连载。"
      style="margin-bottom: 16px"
    />

    <div class="cols">
      <section class="panel">
        <h3 class="panel-title">作品信息</h3>
        <el-form label-width="80px">
          <el-form-item label="书名" required>
            <el-input v-model="form.title" maxlength="100" placeholder="请输入书名" />
          </el-form-item>
          <el-form-item label="分类" required>
            <el-select
              v-model="form.categoryId"
              placeholder="选择分类"
              :disabled="finished"
              style="width: 100%"
            >
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="笔名">
            <el-input v-model="form.author" maxlength="50" :disabled="finished" placeholder="留空则沿用当前笔名" />
          </el-form-item>
          <el-form-item label="标签">
            <el-input v-model="form.tags" maxlength="200" :disabled="finished" placeholder="逗号分隔，如：重生,逆袭" />
          </el-form-item>
          <el-form-item label="简介">
            <el-input
              v-model="form.intro"
              type="textarea"
              :rows="4"
              maxlength="1000"
              show-word-limit
              :disabled="finished"
              placeholder="一句话说清这本书讲什么"
            />
          </el-form-item>
          <el-form-item label="封面">
            <div class="cover-row">
              <div class="cover-thumb">
                <img v-if="form.coverUrl" :src="form.coverUrl" alt="封面" />
                <span v-else class="thumb-ph">暂无</span>
              </div>
              <div class="cover-ops">
                <el-button :loading="coverGenerating" @click="generateCoverImg">
                  <el-icon><MagicStick /></el-icon><span>AI 生成</span>
                </el-button>
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
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">提交修改审核</el-button>
            <el-button @click="router.push(`/novel/${novelId}`)">取消</el-button>
          </el-form-item>
        </el-form>
      </section>

      <section class="panel">
        <h3 class="panel-title">封面预览</h3>
        <div class="cover-preview">
          <img v-if="form.coverUrl" :src="form.coverUrl" alt="封面预览" />
          <span v-else class="cover-ph">未设置封面（作品页将使用主题色书封）</span>
        </div>

        <template v-if="diffRows.length || coverChanged">
          <h3 class="panel-title mt">本次修改（当前 → 修改后）</h3>
          <div class="diff">
            <div v-for="[label, from, to] in diffRows" :key="label" class="diff-row">
              <div class="diff-label">{{ label }}</div>
              <div class="diff-from">{{ from || '—' }}</div>
              <div class="diff-to">{{ to || '—' }}</div>
            </div>
            <div v-if="coverChanged" class="diff-row">
              <div class="diff-label">封面</div>
              <div class="diff-cover">
                <img v-if="origin.coverUrl" :src="origin.coverUrl" alt="当前封面" />
                <span v-else>—</span>
              </div>
              <div class="diff-cover">
                <img v-if="origin.pendingCoverUrl" :src="origin.pendingCoverUrl" alt="修改后封面" />
                <span v-else>—</span>
              </div>
            </div>
          </div>
        </template>

        <p class="note">
          章节正文请到「管理章节」修改。已发布章节的改动同样需要审核，审核期间读者看到的仍是原内容。
        </p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 18px;
}
.page-head .section-title { margin-bottom: 0; }
.back { margin-left: auto; font-size: 13px; color: var(--cinnabar); cursor: pointer; }
.back:hover { text-decoration: underline; }

.cols { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr); gap: 16px; align-items: start; }
.panel {
  background: var(--paper-2); border: 1px solid var(--line);
  border-radius: 10px; padding: 18px 20px;
}
.panel-title {
  font-family: var(--serif); font-size: 15px; font-weight: 600; color: var(--ink);
  margin-bottom: 16px;
}
.panel-title.mt { margin-top: 22px; }

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

.cover-preview {
  width: 168px; height: 224px; border-radius: 8px; overflow: hidden;
  border: 1px solid var(--line); background: var(--paper);
  display: flex; align-items: center; justify-content: center;
}
.cover-preview img { width: 100%; height: 100%; object-fit: cover; }
.cover-ph { font-size: 12px; color: var(--muted); text-align: center; padding: 0 14px; line-height: 1.7; }

.diff { display: flex; flex-direction: column; gap: 10px; }
.diff-row { display: grid; grid-template-columns: 48px 1fr 1fr; gap: 8px; align-items: start; font-size: 12.5px; }
.diff-label { color: var(--muted); }
.diff-from { color: var(--muted); text-decoration: line-through; word-break: break-word; }
.diff-to { color: var(--cinnabar); word-break: break-word; }
.diff-cover {
  width: 56px; height: 74px; border-radius: 4px; overflow: hidden;
  border: 1px solid var(--line); background: var(--paper);
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; color: var(--muted);
}
.diff-cover img { width: 100%; height: 100%; object-fit: cover; }

.note { margin-top: 20px; font-size: 12.5px; color: var(--muted); line-height: 1.8; }

@media (max-width: 1100px) {
  .cols { grid-template-columns: 1fr; }
}
</style>
