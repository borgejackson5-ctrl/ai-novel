package com.ainovel.common.code;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通用错误码
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    OK(200, "操作成功"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),

    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),

    // 业务错误码 10000+
    LOGIN_FAIL(10001, "用户名或密码错误"),
    ACCOUNT_DISABLED(10002, "账号已被禁用"),
    REPEAT_SUBMIT(10003, "请勿重复提交"),
    RATE_LIMIT(10004, "操作过于频繁，请稍后重试"),
    INSUFFICIENT_COIN(10005, "虚拟币余额不足"),
    NOVEL_NOT_FOUND(10006, "小说不存在"),
    NOVEL_OFFLINE(10007, "小说已下架"),
    ALREADY_UNLOCKED(10008, "该章节已解锁"),
    AI_GENERATE_FAIL(10009, "AI 生成失败，请稍后重试"),
    ORDER_ALREADY_EXISTS(10010, "订单已存在，请勿重复下单"),
    LOGIN_TOO_MANY(10011, "登录失败次数过多，请 10 分钟后再试"),
    USERNAME_EXISTS(10012, "该用户名已经被注册"),
    EMAIL_EXISTS(10013, "该邮箱已被注册"),
    CODE_ERROR(10014, "验证码错误"),
    CODE_EXPIRED(10015, "验证码已过期，请重新获取"),
    EMAIL_NOT_REGISTERED(10016, "该邮箱未注册，请先获取验证码注册"),
    ACCOUNT_EXISTS(10017, "该用户名或邮箱已被注册"),
    SIGN_ERROR(10018, "支付签名校验失败"),
    SIGN_TIMEOUT(10019, "支付回调已过期"),
    PAY_REPLAY(10020, "支付回调重复，已忽略"),
    ORDER_CLOSED(10021, "订单已关闭"),
    OLD_PASSWORD_ERROR(10022, "原密码错误"),
    FEEDBACK_ALREADY_HANDLED(10023, "该反馈已处理，请勿重复操作"),
    AI_QUOTA_EXHAUSTED(10024, "今天的免费字数已经用完了，明天再来试试"),
    DEMO_ACCOUNT_LOCKED(10025, "演示账号，密码和账号信息不可修改"),
    AI_QUOTA_RESET_DISABLED(10026, "演示环境已关闭 AI 额度重置功能"),
    AI_PLATFORM_BUSY(10027, "现在使用的人有点多，稍后再试试"),
    AI_COVER_LIMIT(10028, "AI 封面生成已达当日上限，请稍后再试或手动上传封面"),

    // 分类字典维护（管理端）
    CATEGORY_NAME_EXISTS(10029, "分类名称已存在"),
    CATEGORY_NOT_FOUND(10030, "分类不存在"),
    CATEGORY_HAS_NOVELS(10031, "该分类下还有作品，无法删除");

    private final int code;
    private final String msg;
}
