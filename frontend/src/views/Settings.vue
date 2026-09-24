<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getMyAiConfig, saveMyAiKey } from '../api'

const loading = ref(false)

// ===== 免费额度（每日重置）+ 自带 Key（可选，默认收起） =====
const myKey = ref('')
const myBaseUrl = ref('')
const myModel = ref('')
const platformBaseUrl = ref('')
const platformModel = ref('')
const remaining = ref(0)
const quotaLimit = ref(30000)
const hasOwnKey = ref(false)
const showOwnKey = ref(false)

// 常用兼容服务：用户想用自己申请的 Key，就得知道它的服务地址该填什么
const providers = [
  { name: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { name: '通义千问', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  { name: 'Kimi', baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  { name: '智谱 GLM', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  { name: '硅基流动', baseUrl: 'https://api.siliconflow.cn/v1', model: 'Qwen/Qwen2.5-7B-Instruct' }
]
const useProvider = (p) => {
  myBaseUrl.value = p.baseUrl
  myModel.value = p.model
}

// 额度按字下发（量级为「万」），直接显示原数字不易阅读，统一由此处理：
// 29800 → '2.98 万'，8600 → '8600'
const fmtUnits = (n) => {
  const v = Number(n) || 0
  if (v < 10000) return String(v)
  const w = v / 10000
  return (Number.isInteger(w) ? w : w.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')) + ' 万'
}

const loadMine = async () => {
  const cfg = await getMyAiConfig()
  if (cfg) {
    myKey.value = cfg.ownKey || ''  // 脱敏后的 key，如 sk-****abcd
    myBaseUrl.value = cfg.baseUrl || ''
    myModel.value = cfg.model || ''
    // 平台当前使用的服务：作为输入框占位提示，用户需先知道「不填时的默认去向」才能决定是否自行填写
    platformBaseUrl.value = cfg.platformBaseUrl || ''
    platformModel.value = cfg.platformModel || ''
    // 剩余次数由服务端算好下发，前端不再自行做减法（口径变更时前端容易不一致）
    remaining.value = cfg.remainingUnits ?? 0
    quotaLimit.value = cfg.quotaLimit ?? 30000
    hasOwnKey.value = !!cfg.hasOwnKey
  }
}

const saveMyKey = async () => {
  loading.value = true
  try {
    await saveMyAiKey({
      apiKey: myKey.value,
      baseUrl: myBaseUrl.value,
      model: myModel.value
    })
    ElMessage.success('已保存，将立即生效')
    await loadMine()
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

onMounted(loadMine)
</script>

<template>
  <div class="settings">
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-header">
          <span class="with-ic"><el-icon><MagicStick /></el-icon>AI 服务</span>
          <span class="tip">每天可免费使用 {{ fmtUnits(quotaLimit) }}字，次日自动恢复</span>
        </div>
      </template>

      <div class="quota-card">
        <div class="quota-num" :class="{ 'is-text': hasOwnKey }">
          {{ hasOwnKey ? '不限' : fmtUnits(remaining) + '字' }}
        </div>
        <div class="quota-label">{{ hasOwnKey ? '已使用自己的 Key，不受免费额度限制' : '今日剩余字数' }}</div>
        <div class="quota-hint">
          {{ hasOwnKey ? '消耗的是你自己的额度' : '每天 0 点恢复为 ' + fmtUnits(quotaLimit) + '字' }}
        </div>
      </div>

      <div class="own-key">
        <button class="own-key-head" type="button" @click="showOwnKey = !showOwnKey">
          <span>使用自己的 Key</span>
          <el-icon class="own-key-arrow" :class="{ open: showOwnKey }"><ArrowRight /></el-icon>
        </button>
        <div v-show="showOwnKey" class="own-key-body">
          <el-form label-width="110px" label-position="left">
            <el-form-item label="我的 API Key">
              <el-input v-model="myKey" type="password" show-password placeholder="可选，填写后不占用免费额度" />
              <div class="hint">不填也能正常使用。填写后用自己的额度生成，不占用本站的免费额度；留空保存则清除</div>
            </el-form-item>
            <el-form-item label="服务地址">
              <el-input v-model="myBaseUrl" :placeholder="platformBaseUrl || '留空则使用本站的 AI 服务'" />
              <div class="hint">用别家的 Key 时这里要一起填：Key 和服务地址得对得上，否则请求会被发到本站的服务上而被拒绝</div>
            </el-form-item>
            <el-form-item label="模型名称">
              <el-input v-model="myModel" :placeholder="platformModel || '留空则使用本站的默认模型'" />
            </el-form-item>
            <el-form-item label="常用服务">
              <div class="providers">
                <button
                  v-for="p in providers"
                  :key="p.name"
                  class="provider-chip"
                  type="button"
                  @click="useProvider(p)"
                >{{ p.name }}</button>
              </div>
              <div class="hint">点一下自动填好该服务的地址与模型，再把你在它那里申请的 Key 填到上面</div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="loading" @click="saveMyKey">保存</el-button>
              <el-button @click="loadMine">重置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.settings { max-width: 860px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.tip { font-size: 12px; color: #909399; }
.hint { width: 100%; font-size: 12px; color: #b0b3b8; line-height: 1.6; margin-top: 4px; }
.quota-card {
  text-align: center; padding: 24px 16px;
  background: var(--paper);
  border-radius: 10px;
}
.own-key { margin-top: 18px; border-top: 1px solid rgba(0, 0, 0, 0.06); }
.providers { display: flex; flex-wrap: wrap; gap: 8px; }
.provider-chip {
  padding: 4px 12px;
  font-size: 12px;
  color: #606266;
  background: var(--paper);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 6px;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}
.provider-chip:hover { color: #b03a2e; border-color: #b03a2e; }
.own-key-head {
  display: flex; align-items: center; justify-content: space-between;
  width: 100%; padding: 12px 2px;
  background: none; border: none; cursor: pointer;
  font-size: 13.5px; color: var(--ink-2);
}
.own-key-head:hover { color: var(--primary); }
.own-key-arrow { font-size: 13px; transition: transform 0.2s; }
.own-key-arrow.open { transform: rotate(90deg); }
.own-key-body { padding: 4px 0 6px; }
.quota-num { font-size: 34px; font-weight: 700; color: #b23a2e; line-height: 1.1; }
.quota-num.is-text { font-size: 42px; }
.quota-label { margin-top: 8px; font-size: 14px; color: #606266; }
.quota-hint { margin-top: 6px; font-size: 12px; color: #b0b3b8; }
</style>
