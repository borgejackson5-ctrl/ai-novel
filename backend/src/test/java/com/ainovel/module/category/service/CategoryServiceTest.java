package com.ainovel.module.category.service;

import com.ainovel.module.category.dao.CategoryMapper;
import com.ainovel.module.category.domain.entity.Category;
import com.ainovel.module.category.service.impl.CategoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类服务单测：本地缓存命中 + 写操作后必须失效缓存。
 *
 * <p>缓存的典型问题是「写完之后忘记失效」：表现为改名后前台仍显示旧值，
 * 5 分钟后自行恢复，很难联想到缓存。因此每个写方法都要有一条断言约束。
 *
 * <p>缓存在 {@code CategoryServiceImpl} 内部（Caffeine 私有字段），这里用
 * 「查库次数」间接观测：清理缓存就会重新 selectList，未清理则仍是那一次。
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryMapper categoryMapper;

    private CategoryServiceImpl categoryService;

    @BeforeEach
    void setUp() {
        // listCache 是带初始化器的 final 字段，不参与构造器
        categoryService = new CategoryServiceImpl(categoryMapper);
    }

    private void stubList() {
        when(categoryMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("listAll → 第二次读命中缓存，不再查库")
    void listAll_hitsCache() {
        stubList();

        categoryService.listAll();
        categoryService.listAll();

        verify(categoryMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("新增后缓存失效 → 下次读重新查库（否则新分类 5 分钟内前台看不见）")
    void create_evictsCache() {
        stubList();
        categoryService.listAll();

        categoryService.create(new Category());
        categoryService.listAll();

        verify(categoryMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("修改后缓存失效 → 改名立刻生效")
    void update_evictsCache() {
        stubList();
        categoryService.listAll();

        categoryService.update(new Category());
        categoryService.listAll();

        verify(categoryMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("删除后缓存失效 → 被删的分类立刻从前台导航消失")
    void removeById_evictsCache() {
        stubList();
        categoryService.listAll();

        categoryService.removeById(9L);
        categoryService.listAll();

        verify(categoryMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("管理端列表不读缓存（刚改完要立刻看到结果）")
    void listAllForAdmin_bypassesCache() {
        stubList();

        categoryService.listAllForAdmin();
        categoryService.listAllForAdmin();

        verify(categoryMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("nameExists → 名称空白时直接返回 false，不查库")
    void nameExists_blank_returnsFalse() {
        assertFalse(categoryService.nameExists("   ", null));
        assertFalse(categoryService.nameExists(null, null));
        verify(categoryMapper, never()).selectCount(any());
    }
}
