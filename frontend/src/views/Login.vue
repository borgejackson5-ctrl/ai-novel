<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import { sendCode, resetPassword } from '../api'

const router = useRouter()
const userStore = useUserStore()

const isRegister = ref(false)
const isReset = ref(false)
const form = ref({ identifier: '', username: '', email: '', password: '', confirmPassword: '', code: '' })
const loading = ref(false)
const sending = ref(false)
const countdown = ref(0)
const rememberMe = ref(false)

const USERNAME_RE = /^[a-zA-Z0-9_]{3,20}$/
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

// 登录态且账号像邮箱 → 邮箱登录未注册需验证码
const isEmailLogin = computed(() => !isRegister.value && form.value.identifier.includes('@'))
// 验证码发送目标：重置/注册态取 email，邮箱登录态取 identifier
const codeTarget = computed(() =>
  isReset.value ? form.value.email : isRegister.value ? form.value.email : isEmailLogin.value ? form.value.identifier : ''
)

const startCountdown = () => {
  countdown.value = 60
  const timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) clearInterval(timer)
  }, 1000)
}

const handleSendCode = async () => {
  const target = codeTarget.value
  if (!EMAIL_RE.test(target)) {
    ElMessage.warning('请输入正确的邮箱地址')
    return
  }
  sending.value = true
  try {
    await sendCode({ email: target, scene: isReset.value ? 'reset' : isRegister.value ? 'register' : 'login' })
    ElMessage.success('验证码已发送，请查收邮箱')
    startCountdown()
  } catch (e) {
    // 错误已在拦截器提示
  } finally {
    sending.value = false
  }
}

const handleLogin = async () => {
  const { identifier, password, code } = form.value
  if (!identifier || !password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  loading.value = true
  try {
    await userStore.login(identifier, password, code || undefined, rememberMe.value)
    ElMessage.success('登录成功')
    router.push(userStore.role === 'admin' ? '/admin' : '/')
  } catch (e) {
    // 错误已在拦截器提示（含「邮箱未注册 → 请获取验证码」）
  } finally {
    loading.value = false
  }
}

const handleRegister = async () => {
  const { username, email, password, confirmPassword, code } = form.value
  if (!USERNAME_RE.test(username)) {
    ElMessage.warning('用户名需为 3-20 位字母、数字或下划线')
    return
  }
  if (!EMAIL_RE.test(email)) {
    ElMessage.warning('请输入正确的邮箱地址')
    return
  }
  if (!code) {
    ElMessage.warning('请输入邮箱验证码')
    return
  }
  if (!password || password.length < 6) {
    ElMessage.warning('密码长度至少 6 位')
    return
  }
  if (password !== confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    await userStore.register({ username, email, password, code })
    ElMessage.success('注册成功，已自动登录')
    router.push(userStore.role === 'admin' ? '/admin' : '/')
  } catch (e) {
    // 错误已在拦截器提示
  } finally {
    loading.value = false
  }
}

const switchMode = () => {
  isRegister.value = !isRegister.value
  form.value.password = ''
  form.value.confirmPassword = ''
  form.value.code = ''
  loading.value = false
}

const goReset = () => {
  isReset.value = true
  form.value.email = ''
  form.value.code = ''
  form.value.password = ''
  form.value.confirmPassword = ''
  loading.value = false
}

const backToLogin = () => {
  isReset.value = false
  form.value.email = ''
  form.value.code = ''
  form.value.password = ''
  form.value.confirmPassword = ''
  loading.value = false
}

const handleReset = async () => {
  const { email, password, code } = form.value
  if (!EMAIL_RE.test(email)) {
    ElMessage.warning('请输入正确的邮箱地址')
    return
  }
  if (!code) {
    ElMessage.warning('请输入邮箱验证码')
    return
  }
  if (!password || password.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  loading.value = true
  try {
    await resetPassword({ email, code, newPassword: password })
    ElMessage.success('密码已重置，请重新登录')
    backToLogin()
  } catch (e) {
    // 错误已在拦截器提示
  } finally {
    loading.value = false
  }
}

// 快速体验：admin/user 为公开演示账号（DataInitializer 种子，密码 admin123/user123），
// 演示账号的登录提示。密码是公开的演示值，若后台改过演示账号密码则需同步此处。
const quickFill = (u, p) => {
  form.value.identifier = u
  form.value.password = p
}
</script>

<template>
  <div class="login-wrap">
    <div class="login-panel">
      <div class="brand-side">
        <div class="bs-brand">
          <span class="bs-logo"><el-icon><Reading /></el-icon></span>
          <div>
            <div class="bs-name">灵阅</div>
            <div class="bs-sub">小说阅读与创作平台</div>
          </div>
        </div>
        <div class="bs-slogan">
          <div class="bs-title">把灵感<br />变成下一部小说</div>
          <div class="bs-desc">AI 起名简介 · 智能审核 · 平台币解锁<br />一站式小说创作与阅读</div>
        </div>
        <ul class="bs-features">
          <li>
            <span class="f-ic"><el-icon><Document /></el-icon></span>
            <div class="f-text"><b>AI 智能创作</b><small>一键生成书名与简介，专注写正文</small></div>
          </li>
          <li>
            <span class="f-ic"><el-icon><Rank /></el-icon></span>
            <div class="f-text"><b>实时热门榜单</b><small>热度实时计算，Top 榜每周更新</small></div>
          </li>
          <li>
            <span class="f-ic"><el-icon><Collection /></el-icon></span>
            <div class="f-text"><b>海量书库</b><small>多分类精选，平台币一键解锁</small></div>
          </li>
        </ul>
        <div class="bs-tags">
          <span>小说创作</span><span>AI 起名</span><span>内容分发</span><span>在线阅读</span>
        </div>
      </div>

      <div class="form-side">
        <template v-if="isReset">
          <div class="reset-title">重置密码</div>
          <div class="form-tip">输入注册邮箱获取验证码，重设新密码</div>
          <el-form @submit.prevent="handleReset">
            <el-form-item>
              <el-input v-model="form.email" placeholder="注册邮箱" size="large" maxlength="100">
                <template #prefix><el-icon><Message /></el-icon></template>
              </el-input>
            </el-form-item>
            <el-form-item>
              <div class="code-row">
                <el-input v-model="form.code" placeholder="邮箱验证码" size="large" maxlength="6">
                  <template #prefix><el-icon><Key /></el-icon></template>
                </el-input>
                <el-button size="large" class="code-btn" :disabled="countdown > 0" :loading="sending" @click="handleSendCode">
                  {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>
            <el-form-item>
              <el-input v-model="form.password" type="password" placeholder="新密码（6-32 位）" size="large" show-password maxlength="32" @keyup.enter="handleReset">
                <template #prefix><el-icon><Lock /></el-icon></template>
              </el-input>
            </el-form-item>
            <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="handleReset">
              重 置 密 码
            </el-button>
          </el-form>
          <div class="switch-line" @click="backToLogin">← 返回登录</div>
        </template>

        <div class="mode-tabs" v-if="!isReset">
          <span :class="{ active: !isRegister }" @click="!isRegister || switchMode()">登 录</span>
          <span :class="{ active: isRegister }" @click="isRegister || switchMode()">注 册</span>
        </div>
        <div class="form-tip" v-if="!isReset">{{ isRegister ? '邮箱验证码注册，立即开启创作之旅' : '支持用户名或邮箱登录，欢迎回来' }}</div>

        <el-form v-if="!isReset" @submit.prevent="isRegister ? handleRegister() : handleLogin()">
          <el-form-item v-if="!isRegister">
            <el-input v-model="form.identifier" placeholder="用户名 / 邮箱" size="large" maxlength="100">
              <template #prefix><el-icon><User /></el-icon></template>
            </el-input>
          </el-form-item>
          <template v-if="isRegister">
            <el-form-item>
              <el-input v-model="form.username" placeholder="用户名（3-20 位字母、数字或下划线）" size="large" maxlength="20">
                <template #prefix><el-icon><User /></el-icon></template>
              </el-input>
            </el-form-item>
            <el-form-item>
              <el-input v-model="form.email" placeholder="邮箱" size="large" maxlength="100">
                <template #prefix><el-icon><Message /></el-icon></template>
              </el-input>
            </el-form-item>
          </template>
          <el-form-item v-if="isRegister || isEmailLogin">
            <div class="code-row">
              <el-input v-model="form.code" placeholder="邮箱验证码" size="large" maxlength="6">
                <template #prefix><el-icon><Key /></el-icon></template>
              </el-input>
              <el-button size="large" class="code-btn" :disabled="countdown > 0" :loading="sending" @click="handleSendCode">
                {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
              </el-button>
            </div>
          </el-form-item>
          <el-form-item>
            <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password maxlength="32" @keyup.enter="isRegister ? handleRegister() : handleLogin()">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="isRegister">
            <el-input v-model="form.confirmPassword" type="password" placeholder="确认密码" size="large" show-password maxlength="32" @keyup.enter="handleRegister">
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item v-if="!isRegister">
            <el-checkbox v-model="rememberMe">记住我（15 天免登录）</el-checkbox>
          </el-form-item>
          <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="isRegister ? handleRegister() : handleLogin()">
            {{ isRegister ? '注 册 并 登 录' : '登 录' }}
          </el-button>
        </el-form>

        <div class="switch-line" v-if="!isReset" @click="switchMode">
          {{ isRegister ? '已有账号？直接去登录 →' : '没有账号？邮箱验证码注册，或用任意新用户名登录将自动开通' }}
        </div>

        <div class="forgot-line" v-if="!isRegister && !isReset" @click="goReset">忘记密码？用邮箱重置 →</div>

        <div class="quick" v-if="!isRegister && !isReset">
          <span class="quick-label">快速体验：</span>
          <el-tag size="small" effect="plain" class="quick-tag" @click="quickFill('admin', 'admin123')">管理员 admin</el-tag>
          <el-tag size="small" effect="plain" class="quick-tag" @click="quickFill('user', 'user123')">普通用户 user</el-tag>
        </div>
      </div>
    </div>

  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh; display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  background: var(--paper);
  padding: 30px 20px;
}

.login-panel {
  width: 920px; max-width: 100%; min-height: 560px;
  display: grid; grid-template-columns: 1.06fr 1fr;
  background: var(--paper-2); border: 1px solid var(--line);
  border-radius: 14px; overflow: hidden;
  box-shadow: 0 18px 48px rgba(60, 50, 35, 0.1);
}

/* ===== 左：品牌氛围 ===== */
.brand-side {
  color: #f2ede4; padding: 42px 40px 34px;
  background: #1c1a17;
  display: flex; flex-direction: column;
}
.bs-brand { display: flex; align-items: center; gap: 12px; }
.bs-logo {
  width: 42px; height: 42px; border-radius: 8px; display: flex; align-items: center; justify-content: center;
  font-size: 22px; background: var(--cinnabar); color: #fff;
}
.bs-name { font-family: var(--serif); font-size: 21px; font-weight: 600; letter-spacing: 4px; }
.bs-sub { font-size: 12px; color: rgba(242, 237, 228, 0.55); margin-top: 3px; letter-spacing: 1px; }
.bs-slogan { margin-top: 48px; }
.bs-title {
  font-family: var(--serif); font-size: 31px; font-weight: 600;
  line-height: 1.45; letter-spacing: 1px;
}
.bs-desc { margin-top: 16px; font-size: 13.5px; line-height: 1.9; color: rgba(242, 237, 228, 0.62); }
.bs-features { list-style: none; margin-top: 36px; display: flex; flex-direction: column; }
.bs-features li {
  display: flex; align-items: center; gap: 13px;
  padding: 15px 0; border-top: 1px solid rgba(242, 237, 228, 0.12);
}
.f-ic {
  width: 34px; height: 34px; border-radius: 7px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center; font-size: 17px;
  background: rgba(242, 237, 228, 0.07); color: #d9b0a6;
}
.f-text { display: flex; flex-direction: column; gap: 2px; }
.f-text b { font-size: 14px; font-weight: 500; }
.f-text small { font-size: 12px; color: rgba(242, 237, 228, 0.5); }
.bs-tags {
  margin-top: auto; padding-top: 30px;
  display: flex; flex-wrap: wrap; gap: 8px;
}
.bs-tags span {
  font-size: 11px; color: rgba(242, 237, 228, 0.6); padding: 4px 12px; border-radius: 4px;
  border: 1px solid rgba(242, 237, 228, 0.16);
}

/* ===== 右：表单 ===== */
.form-side {
  padding: 40px 42px 30px; display: flex; flex-direction: column;
  background: var(--paper-2);
}
.mode-tabs {
  display: flex; border-radius: 8px; background: var(--paper); padding: 4px;
}
.mode-tabs span {
  flex: 1; text-align: center; padding: 9px 0; font-size: 15px; color: var(--muted);
  cursor: pointer; border-radius: 6px; transition: all .18s; user-select: none;
}
.mode-tabs span.active {
  background: var(--paper-2); color: var(--cinnabar); font-weight: 500;
  box-shadow: 0 1px 3px rgba(60, 50, 35, 0.1);
}
.form-tip { font-size: 13px; color: var(--muted); margin: 16px 2px 20px; }
.submit-btn {
  width: 100%; height: 46px; font-size: 15px; font-weight: 500; letter-spacing: 3px;
  background: var(--cinnabar); border: none; border-radius: 8px; margin-top: 2px;
}
.submit-btn:hover { background: var(--cinnabar-d); }
.code-row { display: flex; gap: 10px; width: 100%; }
.code-btn { width: 120px; flex-shrink: 0; }
.switch-line { margin-top: 18px; text-align: center; color: var(--cinnabar); font-size: 12.5px; cursor: pointer; line-height: 1.7; }
.switch-line:hover { text-decoration: underline; }
.forgot-line { margin-top: 8px; text-align: center; color: var(--muted); font-size: 12.5px; cursor: pointer; }
.forgot-line:hover { color: var(--cinnabar); text-decoration: underline; }
.reset-title { font-family: var(--serif); font-size: 20px; font-weight: 600; text-align: center; color: var(--ink); margin: 6px 0 2px; }
.quick { margin-top: auto; padding-top: 26px; text-align: center; color: var(--muted); font-size: 13px; }
.quick-label { margin-right: 4px; }
.quick-tag { cursor: pointer; margin: 0 3px; }

/* 窄屏：单列 */
@media (max-width: 900px) {
  .login-panel { grid-template-columns: 1fr; width: 440px; }
  .brand-side { padding: 26px 30px; }
  .bs-slogan { margin-top: 24px; }
  .bs-title { font-size: 24px; }
  .bs-features, .bs-tags { display: none; }
}
</style>
