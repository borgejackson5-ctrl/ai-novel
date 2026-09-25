package com.ainovel.module.novel.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.common.util.CacheHelper;
import com.ainovel.module.novel.domain.entity.Chapter;
import com.ainovel.module.novel.domain.form.ChapterSaveForm;
import com.ainovel.module.novel.domain.vo.ChapterContentVO;
import com.ainovel.module.novel.domain.vo.ChapterVO;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 章节服务：目录列表（不含正文）+ 正文读取（解锁校验）+ 章节增删改（连载/章节级审核）
 *
 * <p>本接口继承 {@link IService}，实现类 {@code ChapterServiceImpl} 继承 MyBatis-Plus 的
 * {@code ServiceImpl}，这样 {@code saveBatch} 这类通用批量方法仍能通过接口暴露给调用方
 * （TXT 导入器用它批量入库），同时业务方法留在接口上。
 *
 * <p>章节级审核状态机：读者可见 = 审核通过(1) 或 变更待审(3)；新增章待审(0)、
 * 已发布章修改走影子正文（pending_content 暂存新版，审核通过原子替换、拒绝回退旧版）。
 */
public interface ChapterService extends IService<Chapter> {

    /**
     * 分页目录（读者视角）：只投影元信息列，按「书:页:条数」缓存。
     * 大书（上千章）不再一次性拉全量，主页目录/阅读器抽屉按页拉取。
     */
    public PageResult<ChapterVO> pageVOByNovel(Long novelId, long pageNum, long pageSize);

    /**
     * 作者/管理员视角目录：返回全部章节（含审核状态与拒绝原因），不过滤、不缓存。
     * 供章节管理页展示，需实时反映审核状态变化。
     *
     * <p>**必须分页**：单本作品上千章较为常见，章节管理页一次拉取全量既慢且结果过长。
     */
    public PageResult<ChapterVO> pageAuthorVOByNovel(Long novelId, long pageNum, long pageSize);

    /**
     * 按作品查章节元数据，**不做归属校验**（与 {@link #pageAuthorVOByNovel} 的唯一差别）。
     *
     * <p>供「数据范围已由调用方限定」的内部调用使用：AI 审查工具运行于 MQ 消费线程，
     * 该线程**没有登录上下文**，调用 {@code requireOwnerNovel} 只会抛出「无权限操作该作品」。
     * 整本审查的权限在任务创建时（{@code AiReviewTaskService.start}）已确认，
     * 工具仅需按 novelId 读取。
     *
     * <p>需明确其代价：**调用方必须自行保证 novelId 可信**（来自 toolContext，而非模型提供）。
     * 这也是「工具的数据范围由显式上下文限定，不依赖登录态」这一设计的落点。
     */
    public PageResult<ChapterVO> pageChapterMetaByNovel(Long novelId, long pageNum, long pageSize);

    /**
     * 单章元数据（标题/字数/价格），供阅读器锁卡片与当前章展示。
     * 正文接口 {@link #getContent} 对付费章会拒绝返回，故锁章需单独取元数据。
     *
     * <p>面向读者，因此包含可见性判定：所属作品不可读或本章未过审（非作者/管理员）时抛
     * {@code NOT_FOUND}。返回对象含审核状态与驳回理由，缺少该判定即可按 id 递增枚举未过审章节。
     */
    public ChapterVO getChapterVO(Long id);

    /**
     * 单章元数据，**不做可见性判定**（与 {@link #getChapterVO} 的唯一差别）。
     *
     * <p>供「数据范围已由调用方限定」的内部调用使用，理由同
     * {@link #pageChapterMetaByNovel}：AI 审查任务运行于 MQ 消费线程，
     * 该线程没有登录上下文，复用读者侧方法会把未公开作品的章节判为不存在，
     * 而调用方把「章节不存在」当作该章未审成记录 —— 表现为审核结果静默缺失。
     *
     * <p>调用方必须自行保证 id 可信（来自任务上下文，而非外部输入）。
     */
    public ChapterVO getChapterMetaById(Long id);

    /**
     * 全书章节的**轻量目录**（仅投影 id / 章号 / 标题 / 字数，不含正文），按章号升序。
     *
     * <p>供内部批量遍历使用（当前为 AI 全文审查的任务派发）：
     * <ul>
     *   <li>**不做权限校验**：调用方已在入口确认归属。此处若再次调用
     *       {@code requireOwnerNovel}，因 MQ 消费线程无登录上下文会直接拒绝；</li>
     *   <li>**不使用分页插件**：分页插件会静默截断 size，使「本页不满即取完」
     *       这一判据失真（首页即误判为取完，仅派发部分章节且不报错）；</li>
     *   <li>**仅投影必要列**：{@code content} 为大字段，整本拉取全量正文为几百兆量级。</li>
     * </ul>
     */
    public List<ChapterVO> listChapterBriefs(Long novelId);

    /**
     * 失效分页目录缓存：章节变更（新增/删除/审核状态变化/覆盖导入重建）后立即清除。
     *
     * <p>目录缓存**仅分页一种**（原有一份「整本一次性拉全量」的目录缓存，
     * 随前端不再调用而移除），因此此处只需按模式清除分页缓存。
     * 使用 SCAN 而非 keys，见 {@link CacheHelper#evictByPattern}。
     */
    public void evictListCache(Long novelId);

    /**
     * 读取章节正文：免费章直接放行；付费章需「本章已解锁」或「整本已解锁」，否则拒绝。
     * 待审/拒绝章节仅作者本人/管理员可见（变更待审章读者仍见旧版 content）。
     *
     * <p>正文走缓存，但**权限判断在缓存之外**：
     * <ol>
     *   <li>先以「元数据投影」查询一行小字段（正文为大字段，判断可见性时无需读出）；</li>
     *   <li>权限通过后再取正文，且正文按「章节」维度缓存：同一章正文对所有用户相同，
     *       可见性属权限问题而非内容差异，因此缓存中只可能是「已允许访问的正文」。</li>
     * </ol>
     * 若将带权限判断的结果整体缓存则会产生串号：付费章的判断依赖用户，缓存命中后会返回其他用户的结果。
     */
    public ChapterContentVO getContent(Long chapterId);

    /**
     * 失效某本书的章节正文缓存：章节内容/审核状态变更后调用。
     *
     * <p>正文缓存不带用户，所以只能靠「写时失效」保证不返回旧正文；
     * key 里带上 novelId 正是为了让这里能按书一次性清掉。
     */
    public void evictContentCache(Long novelId);

    // ==================== 章节增删改（连载 + 章节级审核） ====================

    /**
     * 新增章节（连载）：章序号 = 当前最大 + 1，audit_status=待审，读者不可见，发 AI 预审。
     *
     * <p>并发安全：selectMaxChapterNo + 1 非原子，并发新增同一本书可能读到相同 max、
     * 撞唯一索引 (novel_id, chapter_no)。唯一索引兜底 + 有限乐观重试（重读 max 再插）。
     * MySQL InnoDB 唯一键冲突是语句级错误，不会毒化事务，可在同一事务内重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO addChapter(Long novelId, ChapterSaveForm form);

    /**
     * 修改章节（影子正文状态机）：
     * <ul>
     *   <li>已发布章(1)：正文存 pending_content，audit_status→变更待审(3)，读者继续见旧版；</li>
     *   <li>待审(0)/拒绝(2)/变更待审(3)：直接改 content，重新待审(0)。</li>
     * </ul>
     * 标题与价格直接生效；首章强制免费。
     */
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO updateChapter(Long id, ChapterSaveForm form);

    /**
     * 删除章节：读者可见章节（已通过 1 / 变更待审 3）拒绝删除（避免已付费读者退币纠纷），
     * 仅待审(0)/拒绝(2)章可逻辑删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteChapter(Long id);

    /**
     * 重算小说聚合字段（总章数/总字数/整本打包价），只统计读者可见章节（audit_status IN 1,3）。
     * 新增待审章不计数，审核通过/删除变更待审章后触发。
     */
    public void recountNovel(Long novelId);

    /**
     * 请求重建这一章的向量块索引（投 MQ，由 search 模块消费）。
     *
     * <p>触发时机为「本章正文可能已变更」：不限于作者保存，审核将影子正文
     * 提升为正文 / 驳回丢弃影子正文，同样会改变 {@code Chapter.currentBody()}。
     * 索引使用同一份正文口径，否则会出现「审查工具读到待审稿、RAG 检索到旧正文」。
     *
     * <p>必须**投递消息而非同步调用**：单次重建需调用外部向量服务（网络往返 + 费用），
     * 置于保存事务中会将接口延迟交由第三方决定。
     */
    public void requestChunkReindex(Long novelId, Long chapterId);
}
