package com.ainovel.module.admin.service;

import com.ainovel.module.admin.domain.form.CategoryForm;
import com.ainovel.module.admin.domain.vo.CategoryAdminVO;

import java.util.List;

/**
 * 分类字典的管理端维护
 *
 * <p>编排置于 admin 而非 category 的原因：删除前需判断该分类下是否仍有作品，
 * 而作品数只有 novel 模块能提供。若让 category 依赖 novel，会与已有的
 * {@code novel -> category}（详情页回填分类名）构成模块环，因此置于 admin：
 * 该模块本身即同时依赖这两个模块。
 */
public interface AdminCategoryService {

    /** 全部分类（含已禁用），带各自的作品数 */
    public List<CategoryAdminVO> list();

    public void create(CategoryForm form);

    public void update(Long id, CategoryForm form);

    /**
     * 删除（逻辑删除）。分类下还有作品时拒绝。
     *
     * <p>全库未使用外键：直接删除会使相关作品的 category_id 变为悬空值，
     * 该情况不报错、查询不到，仅表现为从分类导航中消失，难以恢复。
     */
    public void delete(Long id);
}
