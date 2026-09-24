import request from "./request";
import { aiStream } from "../utils/aiStream";

// 认证
export const login = (data) => request.post("/auth/login", data);
export const register = (data) => request.post("/auth/register", data);
export const sendCode = (data) => request.post("/auth/code", data);
export const logout = () => request.post("/auth/logout");
export const resetPassword = (data) => request.post("/auth/reset-password", data);

// 用户
export const getUserInfo = () => request.get("/user/me");
export const changePassword = (data) => request.put("/user/password", data);
export const updateNickname = (data) => request.put("/user/nickname", data);

// 分类
export const getCategories = () => request.get("/category/list");
/** 分类管理（管理端）：含已禁用分类，并带上各自的作品数 */
export const getAdminCategories = () => request.get("/admin/category/list");
export const createCategory = (data) => request.post("/admin/category", data);
export const updateCategory = (id, data) => request.put(`/admin/category/${id}`, data);
export const deleteCategory = (id) => request.delete(`/admin/category/${id}`);

// 小说
export const getNovelPage = (params) => request.get("/novel/page", { params });
/** 管理端作品列表：能看到未过审 / 已下架的作品（对外书库接口只返回可分发的） */
export const getAdminNovelPage = (params) => request.get("/admin/novel/page", { params });
export const searchNovel = (params) => request.get("/novel/search", { params });
export const smartSearchNovel = (data) => request.post("/novel/search/smart", data);
export const getNovelDetail = (id, config) => request.get(`/novel/${id}`, config);
/** 静默探测作品是否可读（失效时不弹全局提示）：用于「继续阅读」这类需要降级的本地入口 */
export const probeNovel = (id) => request.get(`/novel/${id}`, { silent: true });
export const saveNovel = (data) => request.post("/novel/save", data);
export const deleteNovel = (id) => request.delete(`/novel/${id}`);
export const changeNovelStatus = (id, status) =>
  request.post(`/novel/status/${id}/${status}`);
export const readNovel = (id) => request.post(`/novel/${id}/read`, null, { silent: true });
export const likeNovel = (id) => request.post(`/novel/${id}/like`);

// 章节（目录仅提供分页接口：大书一次性拉取全量既慢且长到不可用，后端已下线全量接口）
export const getChapterPage = (novelId, { pageNum = 1, pageSize = 50 } = {}) =>
  request.get(`/chapter/page/${novelId}`, { params: { pageNum, pageSize } });
export const getChapter = (id) => request.get(`/chapter/${id}`);
export const getChapterContent = (chapterId) => request.get(`/chapter/${chapterId}/content`);

// 章节管理（作者：连载 / 改章 / 删章，均重新送审）
export const getAuthorChapters = (novelId, params) => request.get(`/chapter/author/${novelId}`, { params });
export const addChapter = (data) => request.post("/chapter", data);
export const updateChapter = (id, data) => request.put(`/chapter/${id}`, data);
export const deleteChapter = (id) => request.delete(`/chapter/${id}`);
// AI 审查章节（错别字 / 语病 / 标点 / 前后不一致，按正文字数扣免费额度）
export const reviewChapter = (chapterId) => request.post(`/ai/review/chapter/${chapterId}`);
// 全书审查：进度、百分比、状态文案都在服务端算好，前端只负责展示与轮询
export const getReviewOverview = (novelId) => request.get(`/ai/review/novel/${novelId}/overview`);
/** 发起全文审查；data 可选：{ scope: 'ALL'|'RECENT'|'RANGE', recentCount, fromChapterNo, toChapterNo } */
export const startNovelReview = (novelId, data) =>
  request.post(`/ai/review/novel/${novelId}`, data);
export const getReviewTask = (taskId) => request.get(`/ai/review/task/${taskId}`);
export const getReviewIssues = (taskId, params) => request.get(`/ai/review/task/${taskId}/issues`, { params });
export const resumeNovelReview = (taskId) => request.post(`/ai/review/task/${taskId}/resume`);
export const cancelNovelReview = (taskId) => request.post(`/ai/review/task/${taskId}/cancel`);

// AI 写作（续写 / 润色）：流式，不使用 axios，因浏览器端无法获取增量响应体，
// 统一使用 utils/aiStream.js 中的 fetch + ReadableStream
// 续写：data = { content: 整章正文, direction: 接下来想写什么(可空), length: SHORT | MEDIUM | LONG }
export const aiContinueStream = (data, options) => aiStream('/ai/write/continue', data, options);
// 润色：data = { content: 选中的那一段, mode: EXPRESS | COMPACT | VIVID }
export const aiPolishStream = (data, options) => aiStream('/ai/write/polish', data, options);

// 榜单
export const getHotRank = () => request.get("/rank/hot");
/** 榜单：hot 热门 / new 新书 / finished 完本 / collect 收藏 */
export const getRank = (type) => request.get(`/rank/${type}`);

// AI
export const aiGenerate = (data) => request.post("/ai/generate", data);
export const getAiConfig = () => request.get("/ai/config");
export const saveAiConfig = (data) => request.post("/ai/config", data);
// 用户 AI 配置（自带 Key + 平台免费额度）
export const getMyAiConfig = () => request.get("/ai/my-config");
// data: { apiKey, baseUrl, model }，后两项留空表示沿用平台配置
export const saveMyAiKey = (data) => request.post("/ai/my-key", data);

// 发布作品 / 我的作品
export const publishNovel = (data) => request.post("/novel/publish", data);
// 封面：AI 生成（文生图较慢，单独放宽超时）/ 用户上传
export const generateCover = (prompt) =>
  request.post("/cover/generate", { prompt }, { timeout: 120000 });
export const uploadCover = (formData) =>
  request.post("/cover/upload", formData, {
    headers: { "Content-Type": "multipart/form-data" },
  });
// 用户端 TXT 导入解析（仅解析、不写入数据库，返回章节列表供预览编辑）
export const importParseNovel = (formData) =>
  request.post("/novel/import-parse", formData, {
    headers: { "Content-Type": "multipart/form-data" },
  });
export const getMyNovels = (params) => request.get("/novel/mine", { params });
// 某位作者的公开发布作品（作者主页）
export const getNovelsByAuthor = (authorId, params) =>
  request.get(`/novel/by-author/${authorId}`, { params });
// 作者编辑作品信息（变更走送审，审核期间前台仍显示旧值）
export const getMyNovelEdit = (id) => request.get(`/novel/mine/${id}`);
export const submitNovelEdit = (id, data) => request.put(`/novel/mine/${id}`, data);
/** 作者下架（新书保护期内会被拒绝，原因由接口返回） */
export const offlineMyNovel = (id) => request.put(`/novel/mine/${id}/offline`);
/** 申请重新上架（下架满 24h 后可申请，需管理员复核） */
export const reshelveMyNovel = (id) => request.post(`/novel/mine/${id}/reshelve`);
/** 删除作品（需已下架满 7 天，且无读者付费解锁） */
export const deleteMyNovel = (id) => request.delete(`/novel/mine/${id}`);
/** 标记作品已完结（完结后章节与简介等内容锁定，仅可改书名/封面） */
export const finishMyNovel = (id) => request.post(`/novel/mine/${id}/finish`);
/** 申请解除完结（需完结满 3 天，管理员批准后恢复连载） */
export const resumeSerialMyNovel = (id, data) => request.post(`/novel/mine/${id}/resume-serial`, data || {});
/** 作者数据看板（阅读人数 / 章节曲线 / 流失章节 / 收藏数） */
export const getNovelStats = (id) => request.get(`/novel/mine/${id}/stats`);

// 站内信
export const getMessagePage = (params) => request.get("/message/page", { params });
export const getUnreadCount = () => request.get("/message/unread-count");
export const markMessageRead = (id) => request.put(`/message/read/${id}`);
export const markAllMessageRead = () => request.put("/message/read-all");
export const clearReadMessages = () => request.delete("/message/read");

// 管理后台 - 作品审核
export const getAuditPage = (params) => request.get("/admin/audit/page", { params });
export const getPendingAuditCount = () => request.get("/admin/audit/pending-count");
export const auditPass = (id, categoryId) =>
  request.post(`/admin/audit/${id}/pass`, null, categoryId ? { params: { categoryId } } : {});
export const auditReject = (id, data) => request.post(`/admin/audit/${id}/reject`, data);
// 管理后台 - 章节级审核
export const getChapterAuditPage = (params) =>
  request.get("/admin/audit/chapter/page", { params });
export const auditChapterPass = (id) => request.post(`/admin/audit/chapter/${id}/pass`);
export const auditChapterReject = (id, data) =>
  request.post(`/admin/audit/chapter/${id}/reject`, data);
// 管理后台 - 公版书 TXT 导入（编码探测 + 正则分章 + 批量入库）
export const importNovel = (formData) =>
  request.post("/admin/import", formData, {
    headers: { "Content-Type": "multipart/form-data" },
  });

// 阅读进度 / 偏好云同步。
// 四个接口均标 silent：游客态下必然返回 401，而「拉不到云端进度」属预期结果，
// 不标记则游客进入首页即弹出提示或被跳转至登录页
export const getReaderProgress = () => request.get("/reader/progress", { silent: true });
export const saveReaderProgress = (data) => request.put("/reader/progress", data, { silent: true });
export const getReaderPreference = () => request.get("/reader/preference", { silent: true });
export const saveReaderPreference = (data) => request.put("/reader/preference", data, { silent: true });

// 评论
export const getCommentPage = (novelId, params) =>
  request.get(`/comment/page/${novelId}`, { params });
/** 章评（本章说）：按章节维度取评论 */
export const getChapterComments = (chapterId, params) =>
  request.get(`/comment/chapter/${chapterId}`, { params });
export const getCommentReplies = (commentId) =>
  request.get(`/comment/replies/${commentId}`);
export const addComment = (data) => request.post("/comment", data);
export const deleteComment = (id) => request.delete(`/comment/${id}`);
export const likeComment = (id) => request.post(`/comment/${id}/like`);

// 书架 / 阅读历史
export const getBookshelf = (params) => request.get("/bookshelf/list", { params });
export const addBookshelf = (novelId) => request.post(`/bookshelf/${novelId}`);
export const removeBookshelf = (novelId) => request.delete(`/bookshelf/${novelId}`);
/** 当前用户是否已收藏（游客没有书架，静默失败即可） */
export const getBookshelfStatus = (novelId) => request.get(`/bookshelf/${novelId}/status`, { silent: true });
/** 记录阅读历史（游客静默：无账号即无「我的阅读记录」，不应因此中断流程） */
export const recordReadHistory = (data) => request.post("/history/record", data, { silent: true });
export const getReadHistory = (params) => request.get("/history/page", { params });

// 订阅解锁。
// 两个「查解锁状态」的接口均标 silent：游客态下必然返回 401，而「游客尚未解锁任何内容」
// 属预期结果，不标记则游客进入作品详情页即被跳转至登录页
export const unlockNovel = (data) => request.post("/subscribe/unlock", data);
export const checkUnlocked = (novelId, chapterId) =>
  request.get(`/subscribe/check/${novelId}`, { params: { chapterId }, silent: true });
export const getUnlockStatus = (novelId) =>
  request.get(`/subscribe/unlock-status/${novelId}`, { silent: true });

// 虚拟币（充值需走「下单 -> 模拟支付」两步，无直接加币接口）
export const getBalance = () => request.get("/coin/balance");
export const createRecharge = (data) => request.post("/coin/recharge", data);
export const mockPayCoin = (orderNo) => request.post(`/coin/pay/mock/${orderNo}`);
export const cancelOrder = (orderNo) => request.post(`/coin/order/${orderNo}/cancel`);
export const getMyRechargeOrders = (params) => request.get("/coin/orders", { params });

// 管理后台
export const getAdminDashboard = () => request.get("/admin/dashboard");
export const getUserPage = (params) => request.get("/admin/user/page", { params });
export const updateUserStatus = (id, status) =>
  request.put(`/admin/user/${id}/status`, null, { params: { status } });
export const resetAiQuota = (userId) => request.post(`/admin/ai-quota/${userId}/reset`);
export const getRechargeOrderPage = (params) =>
  request.get("/admin/order/recharge/page", { params });
export const getSubscribeOrderPage = (params) =>
  request.get("/admin/order/subscribe/page", { params });

// 用户反馈
export const submitFeedback = (data) => request.post("/feedback", data);
export const getMyFeedback = (params) => request.get("/feedback/my", { params });
// 管理后台 - 用户反馈
export const getFeedbackPage = (params) => request.get("/admin/feedback/page", { params });
/** 待处理反馈数（后台侧栏角标） */
export const getFeedbackPendingCount = () => request.get("/admin/feedback/pending-count");
export const handleFeedback = (id, data) => request.put(`/admin/feedback/${id}/handle`, data);

// 管理后台 - 作品申请工单（解除完结等）
export const getAppealPage = (params) => request.get("/admin/appeal/page", { params });
export const handleAppeal = (id, data) => request.put(`/admin/appeal/${id}/handle`, data);

// 管理后台 - 操作日志（审计追溯）
export const getAdminLogPage = (params) => request.get("/admin/log/page", { params });

// 管理后台 - 系统健康（运行指标 + 慢查询）
export const getSystemMetrics = () => request.get("/admin/system/metrics");
export const resetSystemMetrics = () => request.post("/admin/system/metrics/reset");

// 管理后台 - 检索数据运维（原为两个无前端入口的接口，只能手工发起请求）
export const rebuildSearchIndex = () => request.post("/novel/reindex");
export const reconcileSearchIndex = () => request.post("/admin/search/reconcile");
