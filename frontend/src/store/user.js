import { defineStore } from 'pinia'
import { login as loginApi, register as registerApi, getUserInfo, logout as logoutApi, updateNickname as updateNicknameApi } from '../api'
import { setReadingScope } from '../utils/reading'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    userId: null,
    username: '',
    nickname: '',
    coinBalance: 0,
    role: localStorage.getItem('role') || ''
  }),
  actions: {
    applyLogin(data) {
      this.token = data.token
      this.userId = data.userId
      this.username = data.username
      this.nickname = data.nickname
      this.coinBalance = data.coinBalance
      this.role = data.role || ''
      localStorage.setItem('token', data.token)
      if (this.role) {
        localStorage.setItem('role', this.role)
      } else {
        localStorage.removeItem('role')
      }
      // 阅读痕迹按账号隔离：切换命名空间，避免读到上一个账号的书签/偏好
      setReadingScope(this.userId)
    },
    async login(identifier, password, code, rememberMe) {
      this.applyLogin(await loginApi({ identifier, password, code, rememberMe }))
    },
    async register(data) {
      this.applyLogin(await registerApi(data))
    },
    async updateNickname(nickname) {
      await updateNicknameApi({ nickname })
      this.nickname = nickname
    },
    async fetchUserInfo() {
      const data = await getUserInfo()
      this.userId = data.id
      this.username = data.username
      this.nickname = data.nickname
      this.coinBalance = data.coinBalance
      this.role = data.role || ''
      if (this.role) {
        localStorage.setItem('role', this.role)
      } else {
        localStorage.removeItem('role')
      }
      // 刷新后 store.userId 由这里补上，命名空间要对齐（reading.js 启动时已按缓存兜底）
      setReadingScope(this.userId)
    },
    // 仅清除本地凭证（内存 + localStorage），不发起请求，用于 401 等凭证失效场景
    clearSession() {
      this.token = ''
      this.userId = null
      this.username = ''
      this.nickname = ''
      this.coinBalance = 0
      this.role = ''
      localStorage.removeItem('token')
      localStorage.removeItem('role')
      // 回到游客命名空间：不清理账号自身的痕迹，重新切回该账号时仍可读取
      setReadingScope(null)
    },
    // 主动登出：先通知服务端使会话失效，再清本地凭证
    async logout() {
      try {
        await logoutApi()
      } catch (e) {
        /* 服务端登出失败不阻断本地清理 */
      }
      this.clearSession()
    }
  }
})
