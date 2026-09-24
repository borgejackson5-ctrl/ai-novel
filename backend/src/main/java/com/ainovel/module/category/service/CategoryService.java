package com.ainovel.module.category.service;

import com.ainovel.module.category.domain.entity.Category;
import java.util.List;
import java.util.Map;

/**
 * 分类服务
 *
 * <p>分类属于低频变更的字典数据，却会被排行榜/详情/搜索逐请求读取。
 * 使用本地 Caffeine 缓存 5 分钟，避免每次请求都查库。
 */
public interface CategoryService {

    public List<Category> listAll();

    /**
     * 主动失效分类列表缓存。
     *
     * <p>由 {@code create} / {@code update} / {@code removeById} 三个写路径调用（分类写入口在
     * 管理端 {@code AdminCategoryController}）。写后不失效的话，名称与排序变更在 5 分钟 TTL
     * 到期前不会在前台生效。
     */
    public void evictCache();

    // ==================== 管理端维护（增删改） ====================
    //
    // 三个写方法均在内部失效 Caffeine 缓存，不将清缓存的责任交给调用方，
    // 避免遗漏调用时名称变更在 5 分钟后才生效。

    /**
     * 全部分类（含已禁用），按 sort 升序，供管理端使用。
     *
     * <p>有意不经缓存：管理端修改后需立即看到结果，读到 5 分钟前的快照会被误判为未生效。
     */
    public List<Category> listAllForAdmin();

    /** 按主键取分类；不存在（或已逻辑删除）返回 null */
    public Category getById(Long id);

    /**
     * 名称是否已被占用（逻辑删除的不算，名字可以复用）。
     *
     * @param excludeId 要排除的分类 id（改名时排除自己）；新增传 null
     */
    public boolean nameExists(String name, Long excludeId);

    /** 新增分类（主键由雪花算法生成） */
    public void create(Category category);

    /** 按主键更新（只写 name / sort / status） */
    public void update(Category category);

    /** 逻辑删除 */
    public void removeById(Long id);

    /**
     * id -> 名称 映射
     */
    public Map<Long, String> getNameMap();
}
