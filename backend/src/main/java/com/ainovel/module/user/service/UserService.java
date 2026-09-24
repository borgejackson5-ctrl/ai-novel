package com.ainovel.module.user.service;

import com.ainovel.module.user.domain.entity.User;
import com.ainovel.module.user.domain.vo.UserVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 用户服务
 */
public interface UserService {

    public UserVO getUserVO(Long userId);

    /**
     * 查询用户角色编码：含 admin 角色返回 admin，否则返回 user。
     *
     * @return 角色编码；用户没有任何角色时返回 null
     */
    public String getUserRoleCode(Long userId);

    /**
     * 创建用户（注册 / 邮箱转注册，带邮箱；手机号预留）：BCrypt 加密写入数据库 + 绑定默认普通用户角色。
     *
     * <p>用户名唯一由 t_user.uk_username 兜底，并发下重复注册会抛
     * {@link DuplicateKeyException}（事务自动回滚），由调用方决定提示还是降级。
     *
     * @return 已写入数据库（含主键）的用户
     */
    @Transactional(rollbackFor = Exception.class)
    public User createUser(String username, String rawPassword, String email, String phone);

    /**
     * 受保护账号不允许修改密码/昵称：命中则抛 DEMO_ACCOUNT_LOCKED。
     */
    public void assertNotProtected(User user);

    /**
     * 修改昵称/笔名（登录后）。昵称非唯一，直接覆盖（MyBatis-Plus NOT_NULL 只更新非空字段）。
     */
    public void updateNickname(Long userId, String nickname);

    /**
     * 修改密码（登录后）：校验原密码通过后 BCrypt 更新新密码。
     */
    public void changePassword(Long userId, String oldPassword, String newPassword);

    /**
     * 按主键重置/更新密码（已通过原密码或邮箱验证码校验）：BCrypt 加密后仅更新密码列。
     */
    public void updatePassword(Long userId, String rawPassword);

    /**
     * 按主键取用户（跨模块读取用）。
     *
     * <p>返回实体本身，不做状态过滤、也不组装 VO。
     *
     * @return 用户；不存在（或已逻辑删除）返回 null
     */
    public User getUser(Long userId);

    /**
     * 按主键批量取用户（跨模块读取用），用于列表批量回填，避免逐行回查。
     *
     * @param userIds 用户 ID 集合；null 或空集合返回空列表
     */
    public List<User> listUsers(Collection<Long> userIds);

    /**
     * 批量取「用户 ID → 展示名」映射（昵称优先，昵称为空时回退用户名）。
     *
     * <p>评论、反馈、申诉等列表都要按用户 ID 回填作者名，口径收在这里统一。
     *
     * @param userIds 用户 ID 集合；null 或空集合返回空 Map
     */
    public Map<Long, String> getNicknameMap(Collection<Long> userIds);

    /**
     * 原子扣减书币（防超扣）。
     *
     * <p>失败原因由调用方解释：扣费场景提示余额不足，发奖励场景提示用户不存在。
     * 现有 SQL 的 {@code updated == 0} 无法区分这两种情况（余额判断与逻辑删除判断位于
     * 同一 WHERE 子句），因此此处仅返回布尔值，由调用方决定语义。
     *
     * @return true = 扣减成功（余额充足且用户有效）
     */
    public boolean deductCoin(Long userId, int amount);

    /**
     * 原子增加书币（如充值到账、反馈奖励）。
     *
     * @return true = 增加成功；false = 用户不存在或已注销
     */
    public boolean addCoin(Long userId, int amount);
}
