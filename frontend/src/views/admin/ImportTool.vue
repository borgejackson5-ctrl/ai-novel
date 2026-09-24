<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getCategories, importNovel } from '../../api'

const categories = ref([])
const importing = ref(false)
const file = ref(null)
const form = reactive({ categoryId: null, title: '', author: '', overwrite: false, free: true })
const lastResult = ref(null)

const onFileChange = (uploadFile) => {
  file.value = uploadFile.raw
  if (!form.title) {
    form.title = (uploadFile.name || '').replace(/\.txt$/i, '')
  }
}

const onFileRemove = () => { file.value = null }

const reset = () => {
  file.value = null
  Object.assign(form, { categoryId: null, title: '', author: '', overwrite: false, free: true })
}

const doImport = async () => {
  if (!file.value) return ElMessage.warning('请选择 TXT 文件')
  if (!form.categoryId) return ElMessage.warning('请选择分类')
  if (!form.title.trim()) return ElMessage.warning('请填写书名')

  importing.value = true
  lastResult.value = null
  try {
    const fd = new FormData()
    fd.append('file', file.value)
    fd.append('categoryId', form.categoryId)
    fd.append('title', form.title.trim())
    if (form.author.trim()) fd.append('author', form.author.trim())
    fd.append('overwrite', form.overwrite ? 'true' : 'false')
    fd.append('free', form.free ? 'true' : 'false')
    const res = await importNovel(fd)
    lastResult.value = res || {}
    if (res?.skipped) {
      ElMessage.warning(`《${res.title}》已存在，已跳过（如需替换请勾选「覆盖同名书」）`)
    } else {
      ElMessage.success(`${res?.overwrite ? '覆盖' : ''}导入成功：${res?.chapterCount ?? 0} 章 · ${res?.wordCount ?? 0} 字${form.free ? ' · 全本免费' : ''}（${res?.charset || '编码未识别'}）`)
    }
    reset()
  } finally {
    importing.value = false
  }
}

onMounted(async () => {
  categories.value = (await getCategories()) || []
})
</script>

<template>
  <div class="import-tool">
    <div class="grid">
      <section class="panel">
        <h3 class="panel-title">导入公版书 TXT</h3>
        <el-form label-width="80px">
          <el-form-item label="TXT 文件" required>
            <el-upload
              drag
              action="#"
              accept=".txt"
              :auto-upload="false"
              :limit="1"
              :on-change="onFileChange"
              :on-remove="onFileRemove"
            >
              <div class="up-ic"><el-icon><UploadFilled /></el-icon></div>
              <div class="up-text">拖拽 TXT 到此处，或<em>点击选择</em></div>
            </el-upload>
          </el-form-item>
          <el-form-item label="分类" required>
            <el-select v-model="form.categoryId" placeholder="选择分类" style="width: 100%">
              <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="书名" required>
            <el-input v-model="form.title" placeholder="导入后的书名（默认取文件名）" />
          </el-form-item>
          <el-form-item label="作者">
            <el-input v-model="form.author" placeholder="留空默认「佚名」（选填）" />
          </el-form-item>
          <el-form-item label="覆盖">
            <el-checkbox v-model="form.overwrite">
              覆盖同名书（删旧章节重建，保留简介 / 封面 / 阅读量 / 点赞量）
            </el-checkbox>
          </el-form-item>
          <el-form-item label="定价">
            <el-checkbox v-model="form.free">
              全本免费（公版名著建议勾选；不勾则首章免费、其余按字数定价）
            </el-checkbox>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="importing" @click="doImport">开始导入</el-button>
            <el-button @click="reset">清空</el-button>
          </el-form-item>
        </el-form>
      </section>

      <section class="panel">
        <h3 class="panel-title">处理流程</h3>
        <ul class="steps">
          <li><b>自动识别编码</b>依次尝试 UTF-8 与 GBK，中文不会乱码</li>
          <li><b>自动分章</b>识别「第 X 章 / 回 / 卷」等常见标题格式；识别不到时整篇作为一章</li>
          <li><b>批量保存</b>分批写入，并回填总章节数与总字数</li>
          <li><b>定价规则</b>首章免费，其余章按字数定价</li>
          <li><b>重复导入</b>按书名判断，已存在的书默认跳过；勾选「覆盖同名书」则删旧章节后重建</li>
          <li><b>立即可搜</b>导入完成后作品自动加入搜索，无需额外操作</li>
        </ul>

        <h3 class="panel-title mt">本次结果</h3>
        <div v-if="!lastResult" class="ph">还没有执行导入</div>
        <div v-else class="result">
          <div class="r-row"><span>书名</span><b>{{ lastResult.title || form.title }}</b></div>
          <div class="r-row"><span>章节数</span><b>{{ lastResult.chapterCount ?? '-' }}</b></div>
          <div class="r-row"><span>总字数</span><b>{{ lastResult.wordCount ?? '-' }}</b></div>
          <div class="r-row"><span>识别编码</span><b>{{ lastResult.charset || '-' }}</b></div>
          <div class="r-row"><span>结果</span><b>{{ lastResult.skipped ? '已存在，跳过' : '导入成功' }}</b></div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: minmax(0, 1.1fr) minmax(0, 1fr); gap: 16px; align-items: start; }
.panel {
  background: var(--paper-2); border: 1px solid var(--line);
  border-radius: 10px; padding: 18px 20px;
}
.panel-title {
  font-family: var(--serif); font-size: 15px; font-weight: 600; color: var(--ink);
  margin-bottom: 16px;
}
.panel-title.mt { margin-top: 24px; }
.steps { list-style: none; display: flex; flex-direction: column; gap: 12px; }
.steps li { font-size: 13px; color: var(--muted); line-height: 1.75; }
.steps b { color: var(--ink); font-weight: 500; margin-right: 6px; }
.ph { font-size: 13px; color: var(--muted); }
.result { display: flex; flex-direction: column; gap: 8px; }
.r-row { display: flex; font-size: 13px; }
.r-row span { width: 80px; color: var(--muted); flex-shrink: 0; }
.r-row b { color: var(--ink); font-weight: 500; }
.up-ic { font-size: 32px; color: var(--muted); margin-bottom: 6px; }
.up-text { font-size: 13px; color: var(--muted); }
.up-text em { color: var(--cinnabar); font-style: normal; }
@media (max-width: 1100px) { .grid { grid-template-columns: 1fr; } }
</style>
