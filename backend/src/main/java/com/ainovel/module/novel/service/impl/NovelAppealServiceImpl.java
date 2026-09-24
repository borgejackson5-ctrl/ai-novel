package com.ainovel.module.novel.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MessageTypeConstant;
import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.NovelAppealStatusEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.message.service.MessageService;
import com.ainovel.module.novel.dao.NovelAppealMapper;
import com.ainovel.module.novel.dao.NovelMapper;
import com.ainovel.module.novel.domain.entity.Novel;
import com.ainovel.module.novel.domain.entity.NovelAppeal;
import com.ainovel.module.novel.domain.form.AppealHandleForm;
import com.ainovel.module.novel.domain.vo.NovelAppealVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.ainovel.module.novel.service.NovelService;
import com.ainovel.module.novel.service.NovelAppealService;
import com.ainovel.module.user.service.UserService;

/**
 * 作品申请工单：作者提交、管理员处理
 *
 * <p>目前仅有一种类型「解除完结」。实现为独立工单而非复用「意见反馈」，原因见
 * {@link NovelAppeal} 的类注释：反馈的语义是「意见」，管理员动作为采纳/不采纳，
 * 且可能发放奖励币；工单的语义是「业务申请」，动作为批准/驳回，批准后需变更业务状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelAppealServiceImpl implements NovelAppealService {

    private final NovelAppealMapper novelAppealMapper;

    private final NovelMapper novelMapper;

    private final UserService userService;

    private final NovelService novelService;

    private final MessageService messageService;

    /**
     * 管理端工单分页。status 为空则查全部。
     */
    public PageResult<NovelAppealVO> adminPage(int pageNum, int pageSize, Integer status) {
        int safePageNum = Math.max(pageNum, 1);
        long safePageSize = Math.min(Math.max(pageSize, 1), PageParam.MAX_PAGE_SIZE);

        Page<NovelAppeal> page = novelAppealMapper.selectPage(
                new Page<>(safePageNum, safePageSize),
                new LambdaQueryWrapper<NovelAppeal>()
                        .eq(status != null, NovelAppeal::getStatus, status)
                        // 待处理的排在前面，其次按提交时间倒序：管理端最关注是否存在新工单
                        .orderByAsc(NovelAppeal::getStatus)
                        .orderByDesc(NovelAppeal::getId));

        List<NovelAppeal> rows = page.getRecords();
        if (rows.isEmpty()) {
            return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), List.of());
        }

        // 批量回填作品与申请人，避免逐行回查（列表一页 10 条就是 20 次查询）
        Map<Long, Novel> novelMap = novelMapper.selectBatchIds(
                        rows.stream().map(NovelAppeal::getNovelId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Novel::getId, Function.identity()));
        Set<Long> userIds = rows.stream().map(NovelAppeal::getUserId).collect(Collectors.toSet());
        Map<Long, String> nicknameMap = userService.getNicknameMap(userIds);

        List<NovelAppealVO> voList = rows.stream().map(a -> {
            NovelAppealVO vo = BeanUtil.copyProperties(a, NovelAppealVO.class);
            Novel novel = novelMap.get(a.getNovelId());
            if (novel != null) {
                vo.setNovelTitle(novel.getTitle());
                vo.setNovelAuthor(novel.getAuthor());
            }
            vo.setUserNickname(nicknameMap.get(a.getUserId()));
            vo.setTypeText(typeText(a.getType()));
            vo.setStatusText(statusText(a.getStatus()));
            return vo;
        }).toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), voList);
    }

    /**
     * 处理工单：批准则执行业务动作（恢复连载），驳回则仅修改工单状态。
     *
     * <p>两种结果均需向作者发送站内信：作者提交申请后处于等待状态，
     * 需自行翻查列表方可获知处理结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long id, AppealHandleForm form) {
        NovelAppeal appeal = novelAppealMapper.selectById(id);
        if (appeal == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "申请不存在");
        }
        if (appeal.getStatus() == null || appeal.getStatus() != NovelAppealStatusEnum.PENDING.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "该申请已处理，请勿重复操作");
        }
        boolean approved = Boolean.TRUE.equals(form.getApproved());

        NovelAppeal update = new NovelAppeal();
        update.setId(id);
        update.setStatus(approved ? NovelAppealStatusEnum.APPROVED.getCode()
                : NovelAppealStatusEnum.REJECTED.getCode());
        update.setAdminReply(StringUtils.hasText(form.getReply()) ? form.getReply().trim() : null);
        update.setHandleUserId(LoginUserUtil.getUserId());
        update.setHandleTime(LocalDateTime.now());
        novelAppealMapper.updateById(update);

        Novel novel = novelMapper.selectById(appeal.getNovelId());
        String title = novel == null ? "作品" : "《" + novel.getTitle() + "》";

        if (approved) {
            novelService.resumeSerialByAdmin(appeal.getNovelId());
        }

        messageService.send(appeal.getUserId(), MessageTypeConstant.APPEAL_RESULT,
                approved ? "解除完结申请已通过" : "解除完结申请未通过",
                approved
                        ? title + "已恢复为「连载中」，可以继续更新章节了。"
                        : title + "的解除完结申请未通过"
                                + (StringUtils.hasText(update.getAdminReply())
                                        ? "：" + update.getAdminReply() : "。"),
                appeal.getNovelId());

        log.info("处理申请工单: appealId={}, novelId={}, approved={}",
                id, appeal.getNovelId(), approved);
    }

    private static String typeText(String type) {
        return NovelAppeal.TYPE_RESUME_SERIAL.equals(type) ? "解除完结" : type;
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        for (NovelAppealStatusEnum e : NovelAppealStatusEnum.values()) {
            if (e.getCode() == status) {
                return e.getDesc();
            }
        }
        return "";
    }
}
