package com.ainovel.module.feedback.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.MessageTypeConstant;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.enums.CoinTypeEnum;
import com.ainovel.common.enums.FeedbackStatusEnum;
import com.ainovel.common.enums.FeedbackTypeEnum;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.coin.service.CoinService;
import com.ainovel.module.feedback.dao.FeedbackMapper;
import com.ainovel.module.feedback.domain.entity.Feedback;
import com.ainovel.module.feedback.domain.form.FeedbackForm;
import com.ainovel.module.feedback.domain.form.FeedbackHandleForm;
import com.ainovel.module.feedback.domain.vo.FeedbackVO;
import com.ainovel.module.message.service.MessageService;
import com.ainovel.module.user.domain.entity.User;
import com.ainovel.common.domain.PageParam;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.ainovel.module.feedback.service.FeedbackService;
import com.ainovel.module.user.service.UserService;

/**
 * 用户反馈服务：提交 + 我的分页 + 管理员分页 + 处理（采纳奖励虚拟币）
 *
 * <p>匿名反馈仅对管理员隐藏展示身份，user_id 始终写入数据库（用于奖励定位与防刷）。
 */
@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    /** 单笔反馈奖励上限（防误填异常值） */
    private static final int MAX_REWARD = 10000;

    private final FeedbackMapper feedbackMapper;

    private final UserService userService;

    private final CoinService coinService;

    private final MessageService messageService;

    /** 提交反馈 */
    public void submit(FeedbackForm form) {
        Long userId = LoginUserUtil.getUserId();
        if (!FeedbackTypeEnum.isValid(form.getType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "反馈类型不合法");
        }
        Feedback f = new Feedback();
        f.setUserId(userId);
        f.setType(form.getType());
        f.setContent(form.getContent());
        f.setContact(form.getContact());
        f.setAnonymous(form.getAnonymous() != null && form.getAnonymous() == 1 ? 1 : 0);
        f.setStatus(FeedbackStatusEnum.PENDING.getCode());
        f.setRewardCoin(0);
        feedbackMapper.insert(f);
        notifyAdmins(f);
    }

    /**
     * 提醒管理员有新反馈。
     *
     * <p>匿名反馈不写昵称：「匿名」由提交时明确勾选，通知中携带身份将使该选项失效。
     */
    private void notifyAdmins(Feedback f) {
        String who;
        if (f.getAnonymous() != null && f.getAnonymous() == 1) {
            who = "有用户";
        } else {
            User u = userService.getUser(f.getUserId());
            String name = u == null ? String.valueOf(f.getUserId())
                    : (u.getNickname() == null || u.getNickname().isBlank() ? u.getUsername() : u.getNickname());
            who = "用户「" + name + "」";
        }
        messageService.sendToAdmins(MessageTypeConstant.FEEDBACK_SUBMIT,
                "收到新的用户反馈",
                who + "提交了「" + FeedbackTypeEnum.descOf(f.getType()) + "」，请及时处理。",
                f.getId());
    }

    /** 我的反馈分页（新→旧） */
    public PageResult<FeedbackVO> pageMine(int pageNum, int pageSize) {
        Long userId = LoginUserUtil.getUserId();
        Page<Feedback> page = feedbackMapper.selectPage(
                new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)),
                new LambdaQueryWrapper<Feedback>()
                        .eq(Feedback::getUserId, userId)
                        .orderByDesc(Feedback::getId));
        List<FeedbackVO> vos = page.getRecords().stream()
                .map(f -> BeanUtil.copyProperties(f, FeedbackVO.class))
                .toList();
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), vos);
    }

    /** 管理员：反馈分页（可按状态/类型过滤），实名回填用户身份 */
    public PageResult<FeedbackVO> adminPage(int pageNum, int pageSize, Integer status, String type) {
        LambdaQueryWrapper<Feedback> qw = new LambdaQueryWrapper<>();
        if (status != null) {
            qw.eq(Feedback::getStatus, status);
        }
        if (type != null && !type.isBlank()) {
            qw.eq(Feedback::getType, type);
        }
        qw.orderByDesc(Feedback::getId);
        Page<Feedback> page = feedbackMapper.selectPage(new Page<>(PageParam.clampPage(pageNum), PageParam.clampSize(pageSize)), qw);
        List<FeedbackVO> vos = page.getRecords().stream()
                .map(f -> BeanUtil.copyProperties(f, FeedbackVO.class))
                .toList();
        fillIdentity(vos);
        return PageResult.of(page.getTotal(), page.getCurrent(), page.getSize(), vos);
    }

    /**
     * 管理员处理反馈：置状态 + 回复 +（采纳时）奖励虚拟币 + 站内信通知提交者。
     *
     * <p>仅待处理态可处理（幂等防重复奖励）；加币与写入数据库在同一事务中，奖励失败则整体回滚。
     *
     * <p><b>三种结果均需通知</b>（采纳有奖励 / 采纳无奖励 / 未采纳）。此前仅在
     * 「采纳且有奖励」时发送，导致「未采纳」与「采纳但奖励为 0」处理完成后提交者不会收到通知，
     * 连管理员填写的回复也无法看到。通知在写入数据库之后发送，内容才与库中一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handle(Long id, FeedbackHandleForm form) {
        Feedback f = feedbackMapper.selectById(id);
        if (f == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "反馈不存在");
        }
        if (f.getStatus() != FeedbackStatusEnum.PENDING.getCode()) {
            throw new BusinessException(ErrorCode.FEEDBACK_ALREADY_HANDLED);
        }
        int status = form.getStatus();
        if (status != FeedbackStatusEnum.ADOPTED.getCode() && status != FeedbackStatusEnum.REJECTED.getCode()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "处理结果不合法");
        }

        int reward = 0;
        if (status == FeedbackStatusEnum.ADOPTED.getCode()
                && form.getRewardCoin() != null && form.getRewardCoin() > 0) {
            if (form.getRewardCoin() > MAX_REWARD) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "单笔奖励最多 " + MAX_REWARD + " 币");
            }
            reward = form.getRewardCoin();
            coinService.award(f.getUserId(), reward, CoinTypeEnum.FEEDBACK_REWARD, f.getId(), "反馈奖励");
        }

        Feedback update = new Feedback();
        update.setId(id);
        update.setStatus(status);
        update.setReply(form.getReply());
        update.setReplyTime(LocalDateTime.now());
        update.setRewardCoin(reward);
        feedbackMapper.updateById(update);
        notifySubmitter(f, status, reward, form.getReply());
    }

    /** 将处理结果通知提交者（三种结果均发送，附带管理员填写的回复） */
    private void notifySubmitter(Feedback f, int status, int reward, String reply) {
        boolean adopted = status == FeedbackStatusEnum.ADOPTED.getCode();
        StringBuilder sb = new StringBuilder();
        if (adopted && reward > 0) {
            sb.append("你的建议已被采纳，奖励 ").append(reward).append(" 虚拟币，感谢反馈！");
        } else if (adopted) {
            sb.append("你的建议已被采纳，感谢反馈！");
        } else {
            sb.append("很抱歉，这次没能采纳你的建议。");
        }
        if (reply != null && !reply.isBlank()) {
            sb.append(" 管理员回复：").append(reply.trim());
        }
        messageService.send(f.getUserId(), MessageTypeConstant.FEEDBACK_RESULT,
                adopted ? "反馈已被采纳" : "反馈处理结果", sb.toString(), f.getId());
    }

    @Override
    public long pendingCount() {
        return feedbackMapper.selectCount(new LambdaQueryWrapper<Feedback>()
                .eq(Feedback::getStatus, FeedbackStatusEnum.PENDING.getCode()));
    }

    /** 实名反馈回填用户身份（匿名不回填，保护隐私），一次批量查询避免 N+1 */
    private void fillIdentity(List<FeedbackVO> vos) {
        Set<Long> userIds = vos.stream()
                .filter(v -> v.getAnonymous() == null || v.getAnonymous() != 1)
                .map(FeedbackVO::getUserId)
                .collect(Collectors.toSet());
        Map<Long, User> users = userMap(userIds);
        vos.forEach(v -> {
            if (v.getAnonymous() != null && v.getAnonymous() == 1) {
                return;
            }
            User u = users.get(v.getUserId());
            if (u != null) {
                v.setUsername(u.getUsername());
                v.setNickname(u.getNickname());
            }
        });
    }

    private Map<Long, User> userMap(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userService.listUsers(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }
}
