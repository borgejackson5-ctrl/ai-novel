package com.ainovel.module.message.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.ainovel.common.code.ErrorCode;
import com.ainovel.common.constant.RoleConstant;
import com.ainovel.common.domain.PageParam;
import com.ainovel.common.domain.PageResult;
import com.ainovel.common.exception.BusinessException;
import com.ainovel.common.util.LoginUserUtil;
import com.ainovel.module.message.dao.MessageMapper;
import com.ainovel.module.message.domain.entity.Message;
import com.ainovel.module.message.domain.vo.MessageVO;
import com.ainovel.module.user.dao.UserRoleMapper;
import com.ainovel.module.user.domain.entity.UserRole;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import com.ainovel.module.message.service.MessageService;

/**
 * 站内信服务：审核提醒（管理员）与审核结果（作者）共用一套基础设施
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private final MessageMapper messageMapper;

    private final UserRoleMapper userRoleMapper;

    /**
     * 发送站内信（失败不影响主流程，仅记日志）
     */
    public void send(Long userId, String type, String title, String content, Long relatedId) {
        try {
            Message message = new Message();
            message.setUserId(userId);
            message.setType(type);
            message.setTitle(title);
            message.setContent(content);
            message.setRelatedId(relatedId);
            message.setIsRead(0);
            messageMapper.insert(message);
        } catch (Exception e) {
            log.error("站内信发送失败: userId={}, type={}, title={}", userId, type, title, e);
        }
    }

    /**
     * 发送给全部管理员（如「用户 xx 发布了新作品，请审核」）
     */
    public void sendToAdmins(String type, String title, String content, Long relatedId) {
        List<Long> adminIds = findAdminUserIds();
        if (adminIds.isEmpty()) {
            log.warn("未找到管理员用户，站内信未送达: title={}", title);
            return;
        }
        adminIds.forEach(id -> send(id, type, title, content, relatedId));
    }

    /**
     * 我的消息分页（新→旧），{@code isRead} 可选：0 未读 / 1 已读，不传查全部
     */
    public PageResult<MessageVO> page(int pageNum, int pageSize, Integer isRead) {
        Long userId = LoginUserUtil.getUserId();
        int safePageNum = Math.max(1, pageNum);
        int safePageSize = (int) Math.min(Math.max(1, pageSize), PageParam.MAX_PAGE_SIZE);
        Page<Message> page = messageMapper.selectPage(
                new Page<>(safePageNum, safePageSize),
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getUserId, userId)
                        .eq(isRead != null, Message::getIsRead, isRead)
                        .orderByDesc(Message::getId));
        List<MessageVO> voList = page.getRecords().stream()
                .map(m -> BeanUtil.copyProperties(m, MessageVO.class))
                .toList();
        return PageResult.of(page.getTotal(), safePageNum, safePageSize, voList);
    }

    /**
     * 未读数
     */
    public long unreadCount() {
        Long userId = LoginUserUtil.getUserId();
        return messageMapper.selectCount(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getUserId, userId)
                        .eq(Message::getIsRead, 0));
    }

    /**
     * 标记单条已读（校验归属，防止越权读他人消息）
     */
    public void markRead(Long id) {
        Long userId = LoginUserUtil.getUserId();
        Message message = messageMapper.selectById(id);
        if (message == null || !message.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "消息不存在");
        }
        Message update = new Message();
        update.setId(id);
        update.setIsRead(1);
        messageMapper.updateById(update);
    }

    /**
     * 全部已读
     */
    public void markAllRead() {
        Long userId = LoginUserUtil.getUserId();
        messageMapper.update(null, new LambdaUpdateWrapper<Message>()
                .eq(Message::getUserId, userId)
                .eq(Message::getIsRead, 0)
                .set(Message::getIsRead, 1));
    }

    /**
     * 清空已读消息（逻辑删除，返回清除的条数）。
     *
     * <p>两个条件缺一不可：`userId` 防止越权删除他人消息，`isRead = 1` 保证未读消息不被删除。
     * 用户的操作语义是「清除已读」而非「清空收件箱」，一并删除未读会丢失审核结果这类
     * 尚未查看的通知。
     */
    public int clearRead() {
        Long userId = LoginUserUtil.getUserId();
        return messageMapper.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getUserId, userId)
                .eq(Message::getIsRead, 1));
    }

    /**
     * 查询所有管理员用户 ID（绑定 admin 角色的用户）
     */
    private List<Long> findAdminUserIds() {
        return userRoleMapper.selectList(
                        new LambdaQueryWrapper<UserRole>()
                                .eq(UserRole::getRoleId, RoleConstant.ADMIN_ROLE_ID))
                .stream().map(UserRole::getUserId).toList();
    }
}
