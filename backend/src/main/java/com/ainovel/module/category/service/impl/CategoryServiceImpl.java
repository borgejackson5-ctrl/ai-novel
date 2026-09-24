package com.ainovel.module.category.service.impl;

import com.ainovel.module.category.dao.CategoryMapper;
import com.ainovel.module.category.domain.entity.Category;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import com.ainovel.module.category.service.CategoryService;

/**
 * 分类服务
 *
 * <p>分类属于低频变更的字典数据，却会被排行榜/详情/搜索逐请求读取。
 * 使用本地 Caffeine 缓存 5 分钟，避免每次请求都查库。
 */
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    /** 缓存 5 分钟：分类变更频率极低，5 分钟内读到旧数据可接受 */
    private static final long CACHE_MINUTES = 5;

    private final CategoryMapper categoryMapper;

    /** key 固定为 ALL；过期后下次访问自动回源刷新（无穿透风险：只有单 key 单查） */
    private final Cache<String, List<Category>> listCache = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(CACHE_MINUTES, TimeUnit.MINUTES)
            .build();

    private static final String CACHE_KEY_ALL = "ALL";

    public List<Category> listAll() {
        return listCache.get(CACHE_KEY_ALL, key -> categoryMapper.selectList(
                new LambdaQueryWrapper<Category>()
                        .eq(Category::getStatus, 1)
                        .orderByAsc(Category::getSort)));
    }

    /**
     * 主动失效分类列表缓存。
     *
     * <p>由 {@code create} / {@code update} / {@code removeById} 三个写路径调用（分类写入口在
     * 管理端 {@code AdminCategoryController}）。写后不失效的话，名称与排序变更在 5 分钟 TTL
     * 到期前不会在前台生效。
     */
    public void evictCache() {
        listCache.invalidateAll();
    }

    @Override
    public List<Category> listAllForAdmin() {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>()
                .orderByAsc(Category::getSort)
                .orderByAsc(Category::getId));
    }

    @Override
    public Category getById(Long id) {
        return id == null ? null : categoryMapper.selectById(id);
    }

    @Override
    public boolean nameExists(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            return false;
        }
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<Category>()
                .eq(Category::getName, name.trim());
        if (excludeId != null) {
            wrapper.ne(Category::getId, excludeId);
        }
        return categoryMapper.selectCount(wrapper) > 0;
    }

    @Override
    public void create(Category category) {
        categoryMapper.insert(category);
        evictCache();
    }

    @Override
    public void update(Category category) {
        categoryMapper.updateById(category);
        evictCache();
    }

    @Override
    public void removeById(Long id) {
        categoryMapper.deleteById(id);
        evictCache();
    }

    /**
     * id -> 名称 映射
     */
    public Map<Long, String> getNameMap() {
        return listAll().stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
    }
}
