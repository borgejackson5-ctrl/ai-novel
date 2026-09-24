package com.ainovel.module.admin.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.module.admin.domain.form.CategoryForm;
import com.ainovel.module.admin.domain.vo.CategoryAdminVO;
import com.ainovel.module.admin.service.AdminCategoryService;
import com.ainovel.module.category.domain.entity.Category;
import com.ainovel.module.category.service.CategoryService;
import com.ainovel.module.novel.service.NovelService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 分类字典的管理端维护
 */
@Service
@RequiredArgsConstructor
public class AdminCategoryServiceImpl implements AdminCategoryService {

    private final CategoryService categoryService;

    private final NovelService novelService;

    @Override
    public List<CategoryAdminVO> list() {
        Map<Long, Long> counts = novelService.countByCategory();
        return categoryService.listAllForAdmin().stream()
                .map(c -> toVO(c, counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Override
    public void create(CategoryForm form) {
        String name = form.getName().trim();
        if (categoryService.nameExists(name, null)) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_EXISTS, "分类「" + name + "」已存在");
        }
        Category category = new Category();
        category.setName(name);
        category.setParentId(0L);
        category.setSort(form.getSort());
        category.setStatus(form.getStatus());
        categoryService.create(category);
    }

    @Override
    public void update(Long id, CategoryForm form) {
        Category exist = categoryService.getById(id);
        if (exist == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        String name = form.getName().trim();
        if (categoryService.nameExists(name, id)) {
            throw new BusinessException(ErrorCode.CATEGORY_NAME_EXISTS, "分类「" + name + "」已存在");
        }
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setSort(form.getSort());
        category.setStatus(form.getStatus());
        categoryService.update(category);
    }

    @Override
    public void delete(Long id) {
        Category exist = categoryService.getById(id);
        if (exist == null) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        long count = novelService.countByCategory().getOrDefault(id, 0L);
        if (count > 0) {
            throw new BusinessException(ErrorCode.CATEGORY_HAS_NOVELS,
                    "「" + exist.getName() + "」下还有 " + count + " 部作品，先把它们改到别的分类再删");
        }
        categoryService.removeById(id);
    }

    private CategoryAdminVO toVO(Category c, Long count) {
        CategoryAdminVO vo = new CategoryAdminVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setSort(c.getSort());
        vo.setStatus(c.getStatus());
        vo.setNovelCount(count);
        return vo;
    }
}
