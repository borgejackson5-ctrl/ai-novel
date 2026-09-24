package com.ainovel.module.search.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.novel.domain.form.NovelQueryForm;
import com.ainovel.module.novel.domain.vo.NovelVO;
import com.ainovel.module.search.domain.dto.SearchIntent;
import java.util.Set;

/**
 * 小说搜索服务：走 Elasticsearch 全文检索（替代 MySQL LIKE）
 *
 * <p>技术点：
 * <ul>
 *   <li>倒排索引 + IK 分词，解决 LIKE 无法分词/不走索引的问题</li>
 *   <li>multi_match 多字段加权（title 3 倍 > intro 1.5 > tags 1）</li>
 *   <li>BM25 相关度排序 + title/intro 高亮片段</li>
 * </ul>
 */
public interface SearchService {

    /**
     * 关键词搜索（不带书库筛选）
     *
     * @param keyword 关键词
     * @param page    页码（从 1 开始）
     * @param size    每页条数
     */
    public PageResult<NovelVO> search(String keyword, int page, int size);

    /**
     * 关键词搜索（可叠加书库筛选条件）
     *
     * <p>筛选条件（分类 / 连载状态 / 字数区间）由用户主动选择，因此走 filter 硬过滤。
     * 需与智能搜索中模型推断出的分类区分：后者只能作为 should 加分，
     * 因为模型判断错误会直接过滤掉正确结果。
     *
     * @param filter 书库筛选条件，null 表示不筛
     */
    public PageResult<NovelVO> search(String keyword, NovelQueryForm filter, int page, int size);

    /**
     * 智能搜索：按 AI 解析出的结构化意图查询
     *
     * <p>keywords 与 tags 组成必中组（组内 should，命中任一条即可），参与 BM25 打分，
     * 标签命中提权（tags^2.0）以体现 AI 明确识别的题材。
     *
     * <p>推断出的分类必须与用户主动选择的分类区别对待：前者由模型推断，非用户指定，
     * 因此既不能作为 filter，也不能作为普通 should：
     * <ul>
     *   <li>作为 filter：模型分类判断错误会导致零召回。以「西游记」为查询词时，filter 版本返回 0 条。</li>
     *   <li>作为普通 should：分类命中会单独使整个分类通过召回。以「西游记」为查询词时返回 9 条
     *       （《西游记》+ 其余 8 本古典名著），搜索精度被稀释。</li>
     * </ul>
     * 当前实现为「分类仅作兜底」：主查询中分类仅占 0.5 权重（影响排序，不决定召回）；
     * 仅当关键词无任何命中时，才降级使用分类召回一批结果。
     *
     * @param intent AI 解析出的搜索意图（keywords/tags 至少一个非空）
     * @param page   页码（从 1 开始）
     * @param size   每页条数
     */
    public PageResult<NovelVO> smartSearch(SearchIntent intent, int page, int size);

    /**
     * 确保搜索索引存在，且 mapping 按实体注解（@Document/@Field）建立
     *
     * <p>必须显式调用，不能依赖 save/saveAll 自动建索引。
     * Spring Data 在索引缺失时不会按实体映射建索引，而是交由 ES 动态映射处理，
     * 导致 title/intro/tags 上配置的 ik_max_word / ik_smart 全部丢失，
     * 中文索引退化为单字切分。且查询侧仍按词切分，两侧不一致，
     * 表现为搜索无结果或返回大量无关结果（以「三国演义」查询时显式使用 IK 命中 0 条）。
     */
    public void ensureIndex();

    /**
     * 全量重建索引：把 MySQL 中「对外可分发」的小说写入 ES
     *
     * <p>用于：首次启动灌入 seed 数据、ES 索引丢失后的手动重建
     *
     * @return 同步条数
     */
    public int reindexAll();

    /**
     * 按 id 同步一条索引：DB 侧可分发则写入（覆盖），不可见（已删除 / 下架 / 未过审）则从索引移除。
     *
     * <p>MQ 同步消费者与定时对账任务共用该逻辑。两条路径若各自实现一份可见性判断，
     * 最终会产生分歧，这是「可搜索、访问返回 404」最隐蔽的来源。
     *
     * <p>不可见也必须删除：作品下架或审核被驳回后若不从索引移除，
     * 索引中将长期保留可搜索、访问返回 404 的文档。查询时的 status 过滤仅为被动兜底，
     * 索引本身只应存放对外可分发的作品。
     *
     * @return true = 已写入索引；false = 索引中不存在该文档（不可见或已删）
     */
    public boolean syncOne(Long novelId);

    /**
     * 索引中现有文档条数。首次启动据此判断是否需要全量灌入，避免重复写入。
     *
     * <p>仅取总数、不拉取文档，该判断只需要一个数字。
     */
    public long indexedCount();

    /**
     * 拉取索引中的全部文档 id（对账用）。
     *
     * <p>仅取 id 不取 _source：索引中最占空间的是简介和标签，对账只需知道存在哪些 id。
     * 分批拉取，不假设数据量：几十条与几十万条使用同一段代码。
     */
    public Set<Long> loadIndexedIds();
}
