<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { submitFeedback, getMyFeedback } from '../api'
import { fmtTime } from '../utils/format'

const types = [
  { code: 'FEELING', label: '使用感受', desc: '用起来的感觉、体验上的建议' },
  { code: 'SUGGESTION', label: '功能建议', desc: '希望平台新增或改进的功能' },
  { code: 'BUG', label: 'Bug 反馈', desc: '遇到的错误、异常或崩溃' }
]

const form = reactive({ type: 'FEELING', content: '', contact: '', anonymous: false })
const submitting = ref(false)

const list = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10 })

const typeLabel = (code) => types.find((t) => t.code === code)?.label || code
const statusMap = {
  0: { type: 'info', text: '待处理' },
  1: { type: 'success', text: '已采纳' },
  2: { type: 'warning', text: '未采纳' }
}

const submit = async () => {
  if (!form.content.trim()) {
    ElMessage.warning('请填写反馈内容')
    return
  }
  submitting.value = true
  try {
    await submitFeedback({
      type: form.type,
      content: form.content.trim(),
      contact: form.contact.trim() || undefined,
      anonymous: form.anonymous ? 1 : 0
    })
    ElMessage.success('反馈已提交，感谢你的建议！')
    form.content = ''
    form.contact = ''
    form.anonymous = false
    query.pageNum = 1
    load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    submitting.value = false
  }
}

const load = async () => {
  const data = await getMyFeedback(query)
  list.value = data.list
  total.value = data.total
}

onMounted(load)
</script>

<template>
  <div class="feedback">
    <div class="page-head">
      <h2>意见反馈</h2>
      <p class="sub">你的每条反馈我们都会认真看。采纳的 Bug 反馈或合理建议，将获得 <b>虚拟币奖励</b></p>
    </div>

    <el-card shadow="never" class="submit-card">
      <template #header>我要反馈</template>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="反馈类型">
          <el-radio-group v-model="form.type">
            <el-radio-button v-for="t in types" :key="t.code" :value="t.code">
              {{ t.label }}
            </el-radio-button>
          </el-radio-group>
          <div class="type-desc">{{ types.find((t) => t.code === form.type)?.desc }}</div>
        </el-form-item>
        <el-form-item label="反馈内容">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            maxlength="1000"
            show-word-limit
            placeholder="尽量描述清楚：场景、期望、或复现步骤…"
          />
        </el-form-item>
        <el-form-item label="联系方式（选填，便于回访）">
          <el-input v-model="form.contact" maxlength="100" placeholder="邮箱 / 微信等" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.anonymous">匿名提交（对管理员隐藏我的昵称）</el-checkbox>
        </el-form-item>
        <el-button type="primary" size="large" :loading="submitting" @click="submit">提交反馈</el-button>
      </el-form>
    </el-card>

    <el-card shadow="never" class="list-card">
      <template #header>我的反馈</template>
      <div v-if="!list.length" class="empty">还没有提交过反馈～</div>
      <div v-for="f in list" :key="f.id" class="fb-item">
        <div class="fb-top">
          <el-tag size="small" effect="plain">{{ typeLabel(f.type) }}</el-tag>
          <el-tag size="small" :type="statusMap[f.status]?.type" effect="light">{{ statusMap[f.status]?.text }}</el-tag>
          <span v-if="f.anonymous === 1" class="anon">匿名</span>
          <span class="time">{{ fmtTime(f.createTime) }}</span>
        </div>
        <div class="fb-content">{{ f.content }}</div>
        <div v-if="f.rewardCoin > 0" class="fb-reward"><el-icon><Coin /></el-icon> 已奖励 {{ f.rewardCoin }} 虚拟币</div>
        <div v-if="f.reply" class="fb-reply">
          <div class="fb-reply-label">管理员回复：</div>
          <div>{{ f.reply }}</div>
          <div class="fb-reply-time">{{ fmtTime(f.replyTime) }}</div>
        </div>
      </div>
      <div class="pager" v-if="total > query.pageSize">
        <el-pagination background layout="prev, pager, next, total" :total="total"
          :page-size="query.pageSize" :current-page="query.pageNum"
          @current-change="(p) => { query.pageNum = p; load() }" />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.feedback { max-width: 760px; }
.page-head { margin-bottom: 18px; }
.page-head h2 { font-size: 22px; }
.sub { color: var(--muted); font-size: 13px; margin-top: 6px; }
.sub b { color: #e6a23c; }
.type-desc { color: var(--muted); font-size: 12.5px; margin-top: 8px; }
.list-card { margin-top: 18px; }
.empty { text-align: center; color: var(--muted); padding: 24px 0; }
.fb-item { padding: 14px 0; border-bottom: 1px solid #f0f0f5; }
.fb-item:last-child { border-bottom: none; }
.fb-top { display: flex; align-items: center; gap: 8px; }
.anon { font-size: 12px; color: #b0b3b8; }
.time { margin-left: auto; font-size: 12px; color: #b0b3b8; }
.fb-content { margin-top: 8px; color: var(--ink); white-space: pre-wrap; line-height: 1.7; }
.fb-reward { margin-top: 8px; color: #e6a23c; font-size: 13px; }
.fb-reply { margin-top: 10px; padding: 10px 12px; background: #f7f7fb; border-radius: 8px; color: #555; font-size: 13px; line-height: 1.6; }
.fb-reply-label { font-weight: 700; color: #b23a2e; }
.fb-reply-time { margin-top: 4px; font-size: 12px; color: #b0b3b8; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
