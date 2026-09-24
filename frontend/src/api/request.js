import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求拦截器：注入 token
// 注意：后端 Sa-Token 未配置 token-prefix，header 直接使用 Authorization 原文作为 token，
// 与登录时 Set-Cookie 的行为一致（原文 token），因此此处不能添加 "Bearer " 前缀，否则所有请求返回 401。
// token 的权威存储为 localStorage（由 store/user.js 统一读写），此处是底层唯一直接读取点。
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = token
  }
  return config
})

// 统一处理「凭证失效」：清空 store 内存态 + localStorage，并跳登录。
// 去抖：并发多个请求同时 401 时只跳转一次，避免重复 ElMessage / 重复 push。
let redirecting = false
async function handleUnauthorized() {
  try {
    // 动态 import 避免 request.js 与 store/user.js 的循环依赖
    const { useUserStore } = await import('../store/user')
    useUserStore().clearSession()
  } catch (e) {
    localStorage.removeItem('token')
    localStorage.removeItem('role')
  }
  if (!redirecting) {
    redirecting = true
    router.push('/login')
    setTimeout(() => { redirecting = false }, 1000)
  }
}

// 响应拦截器：统一处理错误
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.success === false) {
      if (res.code === 401) {
        handleUnauthorized()
      } else if (!response.config?.silent) {
        // silent：调用方需要自行降级（例如「继续阅读」探测一本已失效的书），
        // 这类探测失败属于预期结果，不应弹出全局错误提示。
        ElMessage.error(res.msg || '请求失败')
      }
      return Promise.reject(res)
    }
    return res.data
  },
  (error) => {
    const status = error.response?.status
    const msg = error.response?.data?.msg || '网络错误'
    // 后端已按 HTTP 状态码语义化：401 未登录 -> 清除凭证并跳登录
    if (status === 401) {
      // 标记 silent 的请求（游客态下探测个人数据：阅读进度 / 偏好 / 书架状态等）
      // 其失败属预期结果，既不弹出提示，也不跳转登录页，否则游客进入首页即被跳转。
      // 其余 401 按原逻辑处理：游客点击「付费章」同样走到此处，需将其引导至登录页。
      if (error.config?.silent) {
        return Promise.reject(error)
      }
      handleUnauthorized()
    } else if (!error.config?.silent) {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  }
)

export default request
