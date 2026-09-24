import { defineStore } from 'pinia'

/**
 * 阅读器全局状态：沉浸全屏开关。
 * App.vue 据此隐藏顶栏/导航并去除主区留白，实现「全视口阅读画布」。
 */
export const useReaderStore = defineStore('reader', {
  state: () => ({
    immersive: false
  }),
  actions: {
    enterImmersive() {
      this.immersive = true
    },
    exitImmersive() {
      this.immersive = false
    }
  }
})
