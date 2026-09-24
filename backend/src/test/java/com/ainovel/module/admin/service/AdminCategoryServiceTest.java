package com.ainovel.module.admin.service;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.admin.domain.form.CategoryForm;
import com.ainovel.module.admin.domain.vo.CategoryAdminVO;
import com.ainovel.module.admin.service.impl.AdminCategoryServiceImpl;
import com.ainovel.module.category.domain.entity.Category;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.novel.service.NovelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分类管理单测：作品数回填、重名校验（含改名时排除自己）、删除保护。
 *
 * <p>重点是删除保护：全库没有外键，删除仍有作品的分类不会报任何错，
 * 那些作品的 category_id 只会变成悬空值，从分类导航中消失且无法查出。
 * 因此该校验必须在单测中约束。
 */
@ExtendWith(MockitoExtension.class)
class AdminCategoryServiceTest {

    @Mock
    private CategoryService categoryService;

    @Mock
    private NovelService novelService;

    private AdminCategoryService adminCategoryService;

    @BeforeEach
    void setUp() {
        adminCategoryService = new AdminCategoryServiceImpl(categoryService, novelService);
    }

    private static Category category(Long id, String name, int sort, int status) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        c.setSort(sort);
        c.setStatus(status);
        return c;
    }

    private static CategoryForm form(String name, int sort, int status) {
        CategoryForm f = new CategoryForm();
        f.setName(name);
        f.setSort(sort);
        f.setStatus(status);
        return f;
    }

    @Test
    @DisplayName("列表 → 按分类回填作品数，没有作品的分类补 0 而不是 null")
    void list_fillsNovelCount() {
        when(categoryService.listAllForAdmin()).thenReturn(List.of(
                category(1L, "古典名著", 1, 1),
                category(7L, "儿女英雄", 6, 1)));
        when(novelService.countByCategory()).thenReturn(Map.of(1L, 6L));

        List<CategoryAdminVO> list = adminCategoryService.list();

        assertEquals(2, list.size());
        assertEquals(6L, list.get(0).getNovelCount());
        assertEquals(0L, list.get(1).getNovelCount());
    }

    @Test
    @DisplayName("新增 → 名称重复时拒绝，不落库")
    void create_duplicateName_throws() {
        when(categoryService.nameExists("志怪神魔", null)).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminCategoryService.create(form("志怪神魔", 1, 1)));

        assertEquals(ErrorCode.CATEGORY_NAME_EXISTS, e.getErrorCode());
        verify(categoryService, never()).create(any());
    }

    @Test
    @DisplayName("新增 → 名称去掉首尾空格，parentId 固定为 0")
    void create_trimsName() {
        when(categoryService.nameExists("新分类", null)).thenReturn(false);

        adminCategoryService.create(form("  新分类  ", 9, 1));

        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryService).create(captor.capture());
        assertEquals("新分类", captor.getValue().getName());
        assertEquals(0L, captor.getValue().getParentId());
        assertEquals(9, captor.getValue().getSort());
    }

    @Test
    @DisplayName("修改 → 分类不存在时拒绝")
    void update_notFound_throws() {
        when(categoryService.getById(404L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminCategoryService.update(404L, form("任意", 1, 1)));

        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("修改 → 重名校验要排除自己，否则原地保存会被自己判成重名")
    void update_sameName_isAllowed() {
        when(categoryService.getById(1L)).thenReturn(category(1L, "古典名著", 1, 1));
        when(categoryService.nameExists("古典名著", 1L)).thenReturn(false);

        adminCategoryService.update(1L, form("古典名著", 3, 1));

        verify(categoryService).nameExists("古典名著", 1L);
        verify(categoryService).update(any(Category.class));
    }

    @Test
    @DisplayName("删除 → 分类下还有作品时拒绝，且错误信息带上具体作品数")
    void delete_withNovels_throws() {
        when(categoryService.getById(1L)).thenReturn(category(1L, "古典名著", 1, 1));
        when(novelService.countByCategory()).thenReturn(Map.of(1L, 6L));

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminCategoryService.delete(1L));

        assertEquals(ErrorCode.CATEGORY_HAS_NOVELS, e.getErrorCode());
        assertTrue(e.getMessage().contains("6"), "错误信息要带作品数，管理员才知道影响面");
        verify(categoryService, never()).removeById(any());
    }

    @Test
    @DisplayName("删除 → 空分类可以删")
    void delete_empty_ok() {
        when(categoryService.getById(7L)).thenReturn(category(7L, "儿女英雄", 6, 1));
        when(novelService.countByCategory()).thenReturn(Map.of());

        adminCategoryService.delete(7L);

        verify(categoryService).removeById(7L);
    }

    @Test
    @DisplayName("删除 → 分类不存在时拒绝")
    void delete_notFound_throws() {
        when(categoryService.getById(404L)).thenReturn(null);

        BusinessException e = assertThrows(BusinessException.class,
                () -> adminCategoryService.delete(404L));

        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, e.getErrorCode());
    }
}
