package com.ainovel.module.search.config;

import com.ainovel.module.search.service.ChapterVectorService;
import com.ainovel.module.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时全量同步搜索索引（幂等：ES 已有数据则跳过）
 *
 * <p>seed 数据直接 insert 到 MySQL，不触发 MQ 增量同步，
 * 因此首次启动需要将已有小说写入 ES；后续变更走 MQ 增量。
 *
 * <p>ES 不可用不阻塞启动：整段逻辑包裹在 try 中，连接失败则记录告警并跳过。
 * 搜索本身有 MySQL 降级兜底，不应因检索引擎未启动导致整个服务无法启动
 * （早期使用 Spring Data Repository，其在构造期即连接 ES，使该兜底失效）。
 *
 * <p>但「索引与配置不一致」必须阻塞启动，这与「ES 未启动」是两种情况：
 * ES 无法修改已存在字段的 analyzer 与 dense_vector 维度，只能人工删除索引后重建。
 * 该状态下服务可正常运行但检索静默失效，其影响大于启动失败（见下方 catch）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchDataInitializer implements CommandLineRunner {

    private final SearchService searchService;

    private final ChapterVectorService chapterVectorService;

    @Override
    public void run(String... args) {
        try {
            // 先确保索引按实体映射建立（含 IK 分析器）。save 不会自动建索引，
            // 索引缺失时将由 ES 动态映射创建出一个没有分词的索引。
            searchService.ensureIndex();
            // 章节向量索引同样需在此确保存在，理由同上；且多一层校验：
            // 混合检索的关键词一路要求 text 可检索（历史索引中为 index=false，
            // match 查询返回 0 条且不报错，混合检索会静默退化为纯向量）
            chapterVectorService.ensureIndex();
            if (searchService.indexedCount() > 0) {
                log.info("ES 索引已有数据，跳过全量同步");
                return;
            }
            int n = searchService.reindexAll();
            log.info("首次启动，全量同步搜索索引完成: {} 条小说", n);
        } catch (IllegalStateException e) {
            // 索引与配置不一致：该情况必须人工处理，不同于「ES 未启动」。
            // 向上抛出使启动失败：启动失败优于「运行中但检索静默失效」，后者不易被发现
            log.error("索引与当前配置不一致，应用无法安全启动：{}", e.getMessage());
            throw e;
        } catch (Exception e) {
            // ES 未就绪时不阻塞应用启动，可用 POST /novel/reindex 手动重建
            log.warn("ES 全量同步跳过（ES 可能未启动）: {}", e.getMessage());
        }
    }
}
