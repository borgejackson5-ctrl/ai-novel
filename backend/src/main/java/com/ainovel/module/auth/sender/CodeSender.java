package com.ainovel.module.auth.sender;

/**
 * 验证码发送渠道（目标地址可为邮箱或手机号，由实现决定）。
 *
 * <p>当前实现：{@link MailCodeSender}（QQ 邮箱 SMTP 实际发送，个人可申请）。
 * 短信渠道预留：生产环境接入真实短信时需注意资质门槛：
 * <ul>
 *   <li>阿里云短信：需企业实名认证，个人无法申请签名/模板；约 0.045 元/条。</li>
 *   <li>腾讯云短信：个人可开通但签名审核受限，正式商用需企业资质 + 网站备案。</li>
 * </ul>
 * 新增短信实现时注入对应 SDK 即可，核心验证码逻辑（生成/存储/校验）无需改动。
 */
public interface CodeSender {

    /**
     * 发送验证码
     *
     * @param target 接收方（邮箱地址或手机号）
     * @param code   验证码
     */
    void send(String target, String code);
}
