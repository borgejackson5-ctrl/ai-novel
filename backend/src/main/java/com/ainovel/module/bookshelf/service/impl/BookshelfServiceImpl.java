package com.ainovel.module.bookshelf.service.impl;

import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.bookshelf.dao.BookshelfMapper;
import com.ainovel.module.bookshelf.domain.entity.Bookshelf;
import com.ainovel.module.bookshelf.domain.vo.BookshelfVO;
import com.ainovel.module.novel.domain.NovelVisibility;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.common.enums.CommonStatusEnum;
import com.ainovel.module.novel.spi.NovelCollectCounter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.ainovel.module.bookshelf.service.BookshelfService;

/**
 * 书架服务：收藏小说（user_id + novel_id 唯一，幂等），列表回填小说信息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookshelfServiceImpl implements BookshelfService, NovelCollectCounter {

    private final BookshelfMapper bookshelfMapper;

    private final NovelService novelService;

    /** 书架分页（最近收藏在前），可按书名关键词过滤，回填小说信息 */
    public PageResult<BookshelfVO> page(int pageNum, int pageSize, String keyword) {
        Long userId = LoginUserUtil.getUserId();
        int safePageNum = Math.max(1, pageNum);
        int safePageSize = (int) Math.min(Math.max(1, pageSize), PageParam.MAX_PAGE_SIZE);
        String kw = keyword == null ? null : keyword.trim();

        Page<Bookshelf> query = new Page<>(safePageNum, safePageSize);
        bookshelfMapper.pageByUser(query, userId, kw);
        List<Bookshelf> rows = query.getRecords();
        if (rows.isEmpty()) {
            return PageResult.of(query.getTotal(), safePageNum, safePageSize, List.of());
        }
        List<Long> novelIds = rows.stream().map(Bookshelf::getNovelId).toList();
        Map<Long, Novel> novelMap = novelService.listNovels(novelIds).stream()
                .collect(Collectors.toMap(Novel::getId, Function.identity()));
        List<BookshelfVO> list = rows.stream().map(r -> toVO(r, novelMap.get(r.getNovelId()))).toList();
        return PageResult.of(query.getTotal(), safePageNum, safePageSize, list);
    }

    /** 加入书架（幂等；校验小说存在且当前可分发） */
    public void add(Long novelId) {
        Long userId = LoginUserUtil.getUserId();
        Novel novel = novelService.getNovel(novelId);
        if (novel == null) {
            throw new BusinessException(ErrorCode.NOVEL_NOT_FOUND);
        }
        // 已下架 / 未过审的作品不再接受新收藏；书架中已有的收藏条目不收回，
        // 由 toVO 标注为「已下架」后保留。
        if (novel.getStatus() == null || novel.getStatus() != CommonStatusEnum.ENABLED.getCode()) {
            throw new BusinessException(ErrorCode.NOVEL_OFFLINE);
        }
        Long count = bookshelfMapper.selectCount(new LambdaQueryWrapper<Bookshelf>()
                .eq(Bookshelf::getUserId, userId)
                .eq(Bookshelf::getNovelId, novelId));
        if (count != null && count > 0) {
            return;
        }
        Bookshelf b = new Bookshelf();
        b.setUserId(userId);
        b.setNovelId(novelId);
        try {
            bookshelfMapper.insert(b);
        } catch (DuplicateKeyException e) {
            // 并发重复收藏，幂等忽略
        }
    }

    /** 移出书架 */
    public void remove(Long novelId) {
        Long userId = LoginUserUtil.getUserId();
        bookshelfMapper.deleteByUserAndNovel(userId, novelId);
    }

    /** 是否已收藏 */
    public boolean contains(Long novelId) {
        Long userId = LoginUserUtil.getUserId();
        Long count = bookshelfMapper.selectCount(new LambdaQueryWrapper<Bookshelf>()
                .eq(Bookshelf::getUserId, userId)
                .eq(Bookshelf::getNovelId, novelId));
        return count != null && count > 0;
    }

    private BookshelfVO toVO(Bookshelf b, Novel n) {
        BookshelfVO vo = new BookshelfVO();
        vo.setNovelId(b.getNovelId());
        vo.setCreateTime(b.getCreateTime());
        if (n == null) {
            // 作品已被逻辑删除：selectBatchIds 查不到（MP 自动过滤已删行）。
            // 返回明确的「已删除」占位对象，而非字段全空的卡片。
            vo.setDeleted(true);
            vo.setOffline(false);
            vo.setNovelTitle("作品已删除");
            return vo;
        }
        vo.setDeleted(false);
        vo.setNovelTitle(n.getTitle());
        vo.setCoverUrl(n.getCoverUrl());
        vo.setAuthor(n.getAuthor());
        vo.setReadCount(n.getReadCount());
        vo.setTotalChapters(n.getTotalChapters());
        // 已下架判定与详情接口保持一致：审核状态可见、但当前未上架
        vo.setOffline(n.getStatus() != null
                && n.getStatus() == CommonStatusEnum.DISABLED.getCode()
                && NovelVisibility.isAuditVisible(n.getAuditStatus()));
        return vo;
    }

    /** {@inheritDoc} 收藏数据存于书架表，此处仅返回统计结果 */
    public long countByNovel(Long novelId) {
        Long n = bookshelfMapper.countByNovel(novelId);
        return n == null ? 0L : n;
    }

    /** {@inheritDoc} */
    @Override
    public List<Long> topNovelIdsByCollect(int limit) {
        return bookshelfMapper.selectIdByCollectDesc(limit,
                CommonStatusEnum.ENABLED.getCode(), NovelVisibility.visibleAuditStatuses());
    }

}
