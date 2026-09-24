/**
 * 阅读痕迹 / 续读书签 持久化（localStorage）
 *
 * 约束：localStorage 按浏览器隔离，不按账号隔离。同一浏览器切换账号登录时，
 * 前一账号写入的键会被新账号读取（新账号进入首页即显示「继续阅读 · 某某书」即源于此）。
 * 因此本模块统一在键名后追加当前账号，是阅读域所有本地键的唯一出入口，
 * 其他位置不得直接书写不带账号后缀的键名。
 *
 * 注意：后端将 Long 雪花 ID 序列化为 JSON 字符串，本模块 novelId / chapterId
 * 一律按字符串存取与比较，禁止使用 Number()（会丢失大整数精度）。
 */

// 本地键名统一在此声明
export const KEY_BOOKMARK = 'reader-last'       // 「继续阅读」书签
export const KEY_POS = 'reader-pos'             // 分章位置：{ "novelId:chapterId": { mode, scrollTop, page } }
export const KEY_SETTINGS = 'reader-settings'   // 阅读偏好（字号 / 主题 / 行距…）

const LEGACY_FONT_KEY = 'reader-font-size' // 更早版本的字号键，只进清理名单，不再读写
const UID_KEY = 'reader-uid'               // 当前命名空间所属账号
const GUEST = 'guest'                      // 未登录时的独立空间

// 模块加载就定好命名空间：App.vue 里的 fetchUserInfo 是不 await 的，
// 首屏渲染时 store.userId 还是 null，只能在这里同步读出来，
// 否则已登录用户刷新后会先按游客身份读取一次，续读栏出现一次短暂闪烁。
let scope = readStoredUid()
let purged = false

function readStoredUid() {
  try {
    return localStorage.getItem(UID_KEY) || GUEST
  } catch (e) {
    return GUEST
  }
}

function scopedKey(base) {
  return `${base}:${scope}`
}

/** 切换命名空间：登录 / 恢复会话时传 userId，登出或凭证失效时传 null */
export function setReadingScope(userId) {
  scope = userId ? String(userId) : GUEST
  try {
    if (userId) localStorage.setItem(UID_KEY, scope)
    else localStorage.removeItem(UID_KEY)
  } catch (e) { /* 忽略 */ }
  purgeLegacy()
}

/**
 * 一次性清理「无账号后缀」的旧键（本次改造之前的格式）。
 * 这些键读取为 undefined 时程序仍可运行，不清理不会报错；但其记录的是某一账号的痕迹，
 * 归属无法判定，先读取者即获得，是账号串号的来源，因此直接删除。
 * 不会造成数据丢失：本人进度与偏好进入页面时分别由 pullProgress() / pullPreference()
 * 从云端恢复（云端是各账号数据的唯一真源，本地仅作缓存）。
 */
function purgeLegacy() {
  if (purged) return
  purged = true
  try {
    ;[KEY_BOOKMARK, KEY_POS, KEY_SETTINGS, LEGACY_FONT_KEY].forEach((k) => localStorage.removeItem(k))
  } catch (e) { /* 忽略 */ }
}

/** 读取当前命名空间下的原始字符串 */
export function readLocal(base) {
  try {
    return localStorage.getItem(scopedKey(base))
  } catch (e) {
    return null
  }
}

export function writeLocal(base, value) {
  try {
    localStorage.setItem(scopedKey(base), value)
  } catch (e) { /* 存储满等异常忽略 */ }
}

export function removeLocal(base) {
  try {
    localStorage.removeItem(scopedKey(base))
  } catch (e) { /* 忽略 */ }
}

/** 读取「继续阅读」书签：{ novelId, novelTitle, chapterId, chapterNo, chapterTitle } */
export function getBookmark() {
  try {
    const bm = JSON.parse(readLocal(KEY_BOOKMARK))
    return bm && bm.novelId != null ? bm : null
  } catch (e) {
    return null
  }
}

export function setBookmark(bm) {
  // 记录本地最后写入时间，供云端 LWW 冲突合并；已有 ts（云端回写）则保留不覆盖
  if (bm && bm.ts == null) bm.ts = Date.now()
  writeLocal(KEY_BOOKMARK, JSON.stringify(bm))
}

export function clearBookmark() {
  removeLocal(KEY_BOOKMARK)
}

/** 读取某章上次位置：{ mode, scrollTop, page } */
export function getChapterPos(novelId, chapterId) {
  try {
    const map = JSON.parse(readLocal(KEY_POS) || '{}')
    return map[`${novelId}:${chapterId}`] || null
  } catch (e) {
    return null
  }
}

export function setChapterPos(novelId, chapterId, pos) {
  try {
    const map = JSON.parse(readLocal(KEY_POS) || '{}')
    map[`${novelId}:${chapterId}`] = pos
    writeLocal(KEY_POS, JSON.stringify(map))
  } catch (e) { /* 忽略 */ }
}

// 模块加载即清理一次旧键（未登录也会走到这里）
purgeLegacy()
