package com.ainovel.module.novel.service;

import com.ainovel.common.constant.NovelConstant;
import com.ainovel.common.domain.PageResult;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.form.NovelEditForm;
import com.ainovel.module.novel.domain.form.NovelForm;
import com.ainovel.module.novel.domain.form.NovelPublishForm;
import com.ainovel.module.novel.domain.form.NovelQueryForm;
import com.ainovel.module.novel.domain.vo.LikeVO;
import com.ainovel.module.novel.domain.vo.NovelEditVO;
import com.ainovel.module.novel.domain.vo.NovelVO;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 小说服务
 */
public interface NovelService {

    public PageResult<NovelVO> page(NovelQueryForm form);

    public NovelVO detail(Long id);

    /**
     * 失效详情缓存：写操作（编辑/删除/上下架）先更 DB 后立即删缓存，Cache-Aside 一致性。
     */
    public void evictDetailCache(Long id);

    /**
     * 按主键取作品（跨模块读取用）。
     *
     * <p>返回实体本身，因此不做可见性过滤、也不组装 VO；可见性由调用方按自身场景判断
     * （对外展示走 {@link #detail} 或查询条件；模块间与管理端读取走这里）。
     *
     * @return 作品；不存在（或已逻辑删除）返回 null
     */
    public Novel getNovel(Long id);

    /**
     * 按主键批量取作品（跨模块读取用），用于列表批量回填，避免逐行回查。
     *
     * @param ids 作品 ID 集合；null 或空集合返回空列表
     */
    public List<Novel> listNovels(Collection<Long> ids);

    /**
     * 用户发布作品：创建小说（待审核、下架）+ 循环建章 + 发 MQ 进入 AI 预审/人工终审链路
     */
    @Transactional(rollbackFor = Exception.class)
    public NovelVO publish(NovelPublishForm form);

    /**
     * 当前用户的作品分页（含审核状态与拒绝原因）
     */
    public PageResult<NovelVO> mine(long pageNum, long pageSize);

    /**
     * 某位作者的公开发布作品（作者主页用）：只返回读者可见的作品。
     *
     * <p>可见性 = 审核通过 或 变更待审，且未下架；否则作者主页会暴露尚未过审的作品。
     */
    public PageResult<NovelVO> byAuthor(Long authorId, long pageNum, long pageSize);

    /**
     * 作品写操作的统一权限门：当前用户为本书作者或管理员，否则拒绝。
     * 与 {@code ChapterService.requireOwnerNovel} 语义一致，避免两处判定口径不一致。
     */
    public Novel requireOwnerNovel(Long novelId);

    // ==================== 作者自助：下架 / 重新上架 / 删除 ====================
    //
    // 三者构成「由轻到重」的处置链：下架 = 停止分发（可逆）；重新上架 = 回到审核队列；
    // 删除 = 终结（不可逆）。动作越重门槛越高，且每一档都需考虑「正在阅读本书的读者」。
    //
    // 管理员强制下架走 changeStatus()，不受新书保护期约束，以保证违规内容可立即处置。

    /**
     * 作者自助下架：立即停止对外分发（书库/榜单/搜索不再出现），
     * 但详情页仍可打开、已解锁章节仍可读，已付费读者的权益不因作者单方面操作被收回。
     */
    public void offlineByAuthor(Long novelId);

    /**
     * 申请重新上架：进入待审核队列，由管理员复核通过后才恢复分发。
     *
     * <p>需经审核而非直接恢复：下架期间内容可能已被大幅修改，
     * 「先下架」本身也可能是规避审核的手段，直接恢复即绕过审核环节；审核同时构成一道冷却期。
     *
     * <p>期间 status 保持下架（不分发），但 auditStatus 置「重新上架待审」使详情仍可访问：
     * 已收藏的读者不应因作者提交申请而失去入口。
     */
    public void requestReshelve(Long novelId);

    /**
     * 作者删除作品（逻辑删除，不可逆）。
     *
     * <p>前置三道：已下架 → 下架满 {@link NovelConstant#DELETE_REQUIRE_OFFLINE_DAYS} 天 →
     * <b>没有读者为它花过币</b>。最后一条为关键条件：删除会使已购读者的书架条目变为「已删除」、
     * 已解锁章节不可读，等同于单方面收回已付费权益。作品存在问题可予下架，
     * 但不应连同他人的付费记录一并删除。
     */
    public void deleteByAuthor(Long novelId);

    // ==================== 作者自助：连载 / 完结 ====================
    //
    // 完结是「创作侧」状态，与上架（分发侧）、审核（合规侧）相互独立。
    // 动因：读者需要「本书是否已写完」的信号，作者也需要一个正式的收尾动作。
    // 代价是完结后内容需锁定（否则可借完结状态反复改动已发布内容），
    // 因此提供「申请恢复」出口：存在恢复路径后才可实施内容锁定。

    /**
     * 作者把自己的作品标记为「已完结」。
     *
     * <p>标记后：章节增删改、简介 / 标签 / 分类 / 笔名的修改都会被拒绝，只放行书名与封面。
     * 记录 {@code finishTime} 作为冷静期起算点。
     */
    public void finishByAuthor(Long novelId);

    /**
     * 作者提交「解除完结」申请：进入管理员工单队列，批准后才恢复为连载中。
     *
     * <p>不由作者直接改回的原因：完结是读者可见的状态（书库与详情页均标注「已完结」），
     * 频繁变更会使该状态失去意义。将恢复入口交由管理员，使作者的完结动作具有约束力。
     * 此外提交受限：转完结后有冷静期，且同一作品不允许存在多个在途申请。
     */
    @Transactional(rollbackFor = Exception.class)
    public void requestResumeSerial(Long novelId, String reason);

    /**
     * 管理员批准解除完结：作品恢复为连载中，章节重新可编辑。
     *
     * <p>清空 {@code finishTime}：冷静期仅约束「刚被作者标记完结」的那一次，
     * 恢复后再次完结会重新写入时间并重新起算。
     */
    public void resumeSerialByAdmin(Long novelId);

    /**
     * 作者编辑页数据：当前生效值 + 待审影子值（仅作品所有者可取）。
     */
    public NovelEditVO editDetail(Long novelId);

    /**
     * 作者提交作品信息变更。
     *
     * <p>不修改正式字段，仅写入影子字段并将 auditStatus 置为「变更待审(3)」：
     * 前台继续显示旧值，管理端审核通过后由 {@code AdminService.auditPass} 整体覆盖，
     * 拒绝则丢弃影子值并回落为「审核通过」。该设计在开放自助编辑的同时，
     * 消除「先以合规内容过审、再改为违规内容」的绕过路径。
     *
     * <p>若作品当前为待审(0)或已拒绝(2)（尚未上架、无人可见），则直接覆盖正式字段并
     * 回到待审，无需经过影子字段，此时不存在「读者已看到旧版」的问题。
     */
    @Transactional(rollbackFor = Exception.class)
    public NovelEditVO submitEdit(Long novelId, NovelEditForm form);

    public NovelVO save(NovelForm form);

    /** 管理员删除（不受作者的冷静期约束，以保证违规内容可立即处置） */
    public void delete(Long id);

    public void changeStatus(Long id, Integer status);

    /** 点赞（toggle：一人一赞可取消） */
    public LikeVO like(Long id);

    /**
     * 记录一次阅读：按用户去重后将作品阅读量 +1，并通知榜单累加热度。
     *
     * <p>去重置于此处而非榜单侧：「是否为本作品的阅读量 +1」属于作品自身的计数逻辑，
     * 榜单仅消费结论。热度经 MQ 异步通知：热度为派生指标，其延迟不影响读者获取正文。
     *
     * @return true = 本次已计入；false = 去重窗口内已计过，跳过
     */
    public boolean recordRead(Long novelId);

    // ==================== 对外可分发作品的查询能力 ====================
    //
    // 榜单、搜索索引、索引对账均需「查询作品」，但各自只需关心自身的排序与遍历方式，
    // 不应直连 t_novel。这一组方法将「何种作品对外可分发」的口径收敛在 novel 模块内
    // （统一取自 NovelVisibility），调用方获取数据而非查询细节。

    /**
     * 按 id 批量取「对外可见」的作品。
     *
     * <p>返回结果可能是入参的子集：不可见（未过审 / 已下架 / 已删）的会被过滤掉。
     * 已确认可见的 id 再次传入过滤一次是幂等的，因此调用方无需自行判断是否需要过滤。
     */
    public List<Novel> listVisibleByIds(Collection<Long> ids);

    /**
     * 榜单候选：最新（雪花 ID 单调递增，按 id 倒序等价于上架时间倒序，顺带走主键索引）。
     */
    public List<Novel> listVisibleLatest(int limit);

    /** 榜单候选：最热（按累计阅读量倒序）。 */
    public List<Novel> listVisibleHottest(int limit);

    /** 榜单候选：完结作品里最热（多一个连载状态条件）。 */
    public List<Novel> listVisibleFinishedHottest(int limit);

    /**
     * 按可见性口径分页遍历作品（id 升序），供索引重建 / 对账全量扫描。
     *
     * @return 本页记录；不足一页即表示已取完（判据应取 {@code page.getSize()}，不使用常量比较）
     */
    public List<Novel> pageVisibleForIndex(int pageNo, int pageSize);

    /** 同上，但仅取 id：对账只需判断存在性，无需取回简介等大字段。 */
    public List<Long> pageVisibleIdsForIndex(int pageNo, int pageSize);

    /**
     * 关键字子串匹配分页（搜索在 ES 不可用时的降级通道）。
     *
     * <p>匹配范围为标题 / 简介 / 标签三字段 OR，**与书库分页刻意不同**（后者仅匹配标题）：
     * 搜索语义本应覆盖简介与标签，降级只应降低速度，不应同时收窄范围。
     */
    public PageResult<Novel> searchByKeyword(String keyword, NovelQueryForm filter, int page, int size);

    /**
     * 各分类下的作品数（分类管理页用）。
     *
     * <p>一次聚合返回全部，避免管理页逐类回查（N+1）；仅统计未逻辑删除的作品。
     * 键为分类 id：该分类下无作品时键不存在（调用方按 0 处理）。
     */
    public Map<Long, Long> countByCategory();
}
