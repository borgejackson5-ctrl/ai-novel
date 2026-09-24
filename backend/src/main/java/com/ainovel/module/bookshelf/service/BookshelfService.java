package com.ainovel.module.bookshelf.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.bookshelf.domain.vo.BookshelfVO;

/**
 * 书架服务：收藏小说（user_id + novel_id 唯一，幂等），列表回填小说信息
 */
public interface BookshelfService {

    /** 书架分页（最近收藏在前），可按书名关键词过滤，回填小说信息 */
    public PageResult<BookshelfVO> page(int pageNum, int pageSize, String keyword);

    /** 加入书架（幂等；校验小说存在且当前可分发） */
    public void add(Long novelId);

    /** 移出书架 */
    public void remove(Long novelId);

    /** 是否已收藏 */
    public boolean contains(Long novelId);
}
