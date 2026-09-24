/**
 * 阅读进度 / 偏好 云同步桥（localStorage 镜像层 ↔ 后端云端真源）
 *
 * 同步策略：
 * - pull：云端有则覆盖本地镜像；无则返回 null，调用方保留本地值。
 * - push：本地写入云端，防抖 1s，失败静默（离线 / 未登录时不中断阅读）。
 *
 * 注意：novelId / chapterId 保持字符串（后端 Long 序列化为 JSON 字符串），
 * 禁止 Number() 转换，避免雪花 ID 丢精度。
 */
import {
  getReaderProgress, saveReaderProgress,
  getReaderPreference, saveReaderPreference
} from '../api'
import { setBookmark, setChapterPos } from './reading'

/** 拉取云端「继续阅读」书签 + 章内位置，有则覆盖本地镜像；返回书签或 null */
export async function pullProgress() {
  try {
    const p = await getReaderProgress()
    if (!p || p.novelId == null || p.chapterId == null) return null
    const bm = {
      novelId: p.novelId,
      novelTitle: p.novelTitle || '',
      chapterId: p.chapterId,
      chapterNo: p.chapterNo,
      chapterTitle: p.chapterTitle || '',
      ts: p.clientTime || 0 // 保留云端写入时间，作为下次 push 的 LWW 基准
    }
    setBookmark(bm)
    // 章内位置写回 reader-pos，打开阅读器时 restorePosition/computePages 自动恢复
    setChapterPos(p.novelId, p.chapterId, {
      mode: p.mode === 'page' ? 'page' : 'scroll',
      scrollTop: p.scrollTop || 0,
      page: p.pageNo || 0
    })
    return bm
  } catch (e) {
    return null
  }
}

let progressTimer = null

/** 推「继续阅读」书签 + 章内位置到云端（防抖 1s，失败静默） */
export function pushProgress(bookmark, pos) {
  if (!bookmark || bookmark.novelId == null || bookmark.chapterId == null) return
  if (progressTimer) clearTimeout(progressTimer)
  progressTimer = setTimeout(async () => {
    progressTimer = null
    try {
      await saveReaderProgress({
        novelId: bookmark.novelId,
        chapterId: bookmark.chapterId,
        novelTitle: bookmark.novelTitle || '',
        chapterNo: bookmark.chapterNo,
        chapterTitle: bookmark.chapterTitle || '',
        mode: pos?.mode,
        scrollTop: pos?.scrollTop,
        pageNo: pos?.page,
        clientTime: bookmark.ts // LWW 基准
      })
    } catch (e) { /* 静默 */ }
  }, 1000)
}

/** 拉取云端阅读偏好；返回原始偏好对象或 null（由调用方校验后应用） */
export async function pullPreference() {
  try {
    const p = await getReaderPreference()
    return p && typeof p === 'object' ? p : null
  } catch (e) {
    return null
  }
}

let prefTimer = null

/** 推阅读偏好到云端（防抖 1s，失败静默） */
export function pushPreference(settings) {
  if (!settings) return
  if (prefTimer) clearTimeout(prefTimer)
  prefTimer = setTimeout(async () => {
    prefTimer = null
    try {
      await saveReaderPreference({
        fontSize: settings.fontSize,
        fontFamily: settings.fontFamily,
        lineHeight: settings.lineHeight,
        columnWidth: settings.columnWidth,
        theme: settings.theme,
        brightness: settings.brightness,
        mode: settings.mode,
        autoSpeed: settings.autoSpeed
      })
    } catch (e) { /* 静默 */ }
  }, 1000)
}
