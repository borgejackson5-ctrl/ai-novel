<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAiConfig, saveAiConfig } from '../../api'

const loading = ref(false)
const form = ref({
  baseUrl: 'https://api.deepseek.com/v1',
  apiKey: '',
  model: 'deepseek-chat',
  temperature: 0.8,
  mockEnabled: true
})

// 保存前后均以此判断是否已配置 Key：仅识别脱敏值以外的内容
const hasKey = computed(() => !!form.value.apiKey)

// 常用兼容服务：切换服务商时地址与模型需同时变更，否则请求会发往旧服务并被拒绝
const providers = [
  { name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { name: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { name: 'Kimi', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  { name: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  { name: '硅基流动', baseUrl: 'https://api.siliconflow.cn/v1', model: 'Qwen/Qwen2.5-7B-Instruct' }
]
const activeProvider = computed(() => providers.find(p => p.baseUrl === form.value.baseUrl)?.name || '自定义')
const useProvider = (p) => {
  form.value.baseUrl = p.baseUrl
  form.value.model = p.model
}

const load = async () => {
  loading.value = true
  try {
    const cfg = await getAiConfig()
    if (cfg) {
      form.value.baseUrl = cfg.baseUrl || 'https://api.deepseek.com/v1'
      form.value.apiKey = cfg.apiKey || ''
      form.value.model = cfg.model || 'deepseek-chat'
      form.value.temperature = cfg.temperature ?? 0.8
      form.value.mockEnabled = cfg.mockEnabled === 1
    }
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

const save = async () => {
  loading.value = true
  try {
    await saveAiConfig({
      baseUrl: form.value.baseUrl,
      apiKey: form.value.apiKey,
      model: form.value.model,
      temperature: form.value.temperature,
      mockEnabled: form.value.mockEnabled
    })
    ElMessage.success('已保存，AI 相关功能立即生效')
    await load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

const reset = () => load()

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="page-head">
      <div>
        <h2 class="section-title">AI 配置</h2>
        <span class="count">这里配置全站共用的 AI 服务，作品审核、内容生成与智能搜索都读这份配置</span>
      </div>
    </div>

    <div class="status-row">
      <div class="status-card">
        <div class="status-value">{{ hasKey ? '已配置' : '未配置' }}</div>
        <div class="status-label">API Key</div>
      </div>
      <div class="status-card">
        <div class="status-value">{{ activeProvider }}</div>
        <div class="status-label">当前服务</div>
      </div>
      <div class="status-card">
        <div class="status-value">{{ form.model || '-' }}</div>
        <div class="status-label">当前模型</div>
      </div>
    </div>

    <el-card shadow="never" class="card">
      <template #header>
        <span class="card-title">服务配置</span>
      </template>

      <el-form label-width="110px" label-position="left">
        <el-form-item label="常用服务">
          <div class="providers">
            <button
              v-for="p in providers"
              :key="p.name"
              class="provider-chip"
              :class="{ on: p.baseUrl === form.baseUrl }"
              type="button"
              @click="useProvider(p)"
            >{{ p.name }}</button>
          </div>
          <div class="hint">点一下自动填好该服务的地址与模型，再把对应的 Key 填到下面</div>
        </el-form-item>

        <el-form-item label="服务地址">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com/v1" />
          <div class="hint">支持任意 OpenAI 兼容服务；换服务商时地址与模型要一起换</div>
        </el-form-item>

        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password placeholder="sk-..." />
          <div class="hint">页面只回显首尾几位，保存时原样保留不动即可；填写后全站 AI 功能走真实模型</div>
        </el-form-item>

        <el-form-item label="模型名称">
          <el-input v-model="form.model" placeholder="deepseek-chat" />
          <div class="hint">如 deepseek-chat、qwen-plus、moonshot-v1-8k</div>
        </el-form-item>

        <el-form-item label="温度">
          <el-slider v-model="form.temperature" :min="0" :max="1.5" :step="0.1" show-input style="max-width: 360px" />
          <div class="hint">越低越稳定、越高越发散；内容生成建议 0.8 左右</div>
        </el-form-item>

        <el-form-item label="演示模式">
          <el-switch v-model="form.mockEnabled" />
          <div class="hint">
            开启后，未配置 Key 时用示例内容代替真实调用，便于本地演示；
            关闭后未配置 Key 的 AI 功能会直接提示暂未开放。正式部署建议关闭
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="loading" @click="save">保存配置</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="card" style="margin-top: 16px">
      <template #header>
        <span class="card-title">如何获取 Key</span>
      </template>
      <ul class="guide">
        <li>DeepSeek：前往 <a href="https://platform.deepseek.com" target="_blank" rel="noopener">platform.deepseek.com</a> 注册并创建 API Key</li>
        <li>通义千问：前往 <a href="https://dashscope.console.aliyun.com" target="_blank" rel="noopener">dashscope.console.aliyun.com</a> 获取</li>
        <li>Kimi：前往 <a href="https://platform.moonshot.cn" target="_blank" rel="noopener">platform.moonshot.cn</a> 获取</li>
      </ul>
    </el-card>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; }
.page-head .section-title { margin-bottom: 4px; }
.count { color: var(--muted); font-size: 12.5px; }

.status-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 18px; }
.status-card { padding: 14px 18px; background: var(--paper); border-radius: 10px; }
.status-value { font-size: 18px; font-weight: 600; color: #b23a2e; line-height: 1.3; }
.status-label { margin-top: 4px; font-size: 12.5px; color: #7c818e; }

.card-title { font-size: 14px; font-weight: 500; }
.hint { width: 100%; font-size: 12px; color: #b0b3b8; line-height: 1.6; margin-top: 4px; }
.guide { padding-left: 20px; color: #606266; line-height: 2; margin: 0; }
.guide a { color: var(--cinnabar); text-decoration: none; }

.providers { display: flex; flex-wrap: wrap; gap: 8px; }
.provider-chip {
  padding: 4px 12px; font-size: 12px;
  border: 1px solid var(--line); border-radius: 6px;
  background: transparent; color: var(--ink);
  cursor: pointer; transition: border-color .18s, color .18s, background .18s;
}
.provider-chip:hover { border-color: var(--cinnabar); color: var(--cinnabar); }
.provider-chip.on { border-color: var(--cinnabar); color: #fff; background: var(--cinnabar); }
</style>
