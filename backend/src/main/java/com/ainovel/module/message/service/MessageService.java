package com.ainovel.module.message.service;

import com.ainovel.common.domain.PageResult;
import com.ainovel.module.message.domain.vo.MessageVO;

/**
 * 站内信服务：审核提醒（管理员）与审核结果（作者）共用一套基础设施
 */
public interface MessageService {

    /**
     * 发送站内信（失败不影响主流程，仅记日志）
     */
    public void send(Long userId, String type, String title, String content, Long relatedId);

    /**
     * 发送给全部管理员（如「用户 xx 发布了新作品，请审核」）
     */
    public void sendToAdmins(String type, String title, String content, Long relatedId);

    /**
     * 我的消息分页（新→旧），{@code isRead} 可选：0 未读 / 1 已读，不传查全部
     */
    public PageResult<MessageVO> page(int pageNum, int pageSize, Integer isRead);

    /**
     * 未读数
     */
    public long unreadCount();

    /**
     * 标记单条已读（校验归属，防止越权读他人消息）
     */
    public void markRead(Long id);

    /**
     * 全部已读
     */
    public void markAllRead();

    /**
     * 清空已读消息（逻辑删除，返回清除的条数）。
     *
     * <p>两个条件缺一不可：`userId` 防止越权删除他人消息，`isRead = 1` 保证未读消息不被删除。
     * 用户的操作语义是「清除已读」而非「清空收件箱」，一并删除未读会丢失审核结果这类
     * 尚未查看的通知。
     */
    public int clearRead();
}
