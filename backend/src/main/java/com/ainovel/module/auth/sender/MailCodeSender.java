package com.ainovel.module.auth.sender;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * 邮箱验证码发送（QQ 邮箱 SMTP，465 SSL）。
 *
 * <p>发件人取 {@code spring.mail.username}：本地配置见 gitignored 的 application-local.yaml，
 * **生产环境通过环境变量 MAIL_USERNAME 注入，不得写入仓库**；授权码同理，不得提交。
 * 切换短信渠道见 {@link CodeSender} 接口注释的资质要求。
 *
 * <p>选用 MimeMessage 而非 SimpleMailMessage：收件箱列表显示的是「显示名」，
 * 显示中文须按 RFC 2047 编码；{@code SimpleMailMessage.setFrom("灵阅 <a@b.com>")}
 * 这种字符串形式**不做编码、中文会乱码**，只有
 * {@link MimeMessageHelper#setFrom(String, String)} 会按声明的 charset 编码。
 * 同时将正文编码固定为 UTF-8，不受平台默认编码影响。
 *
 * <p>注意：显示名仅影响收件箱列表展示，**真实地址在邮件详情中仍然可见**，
 * 这是邮件协议决定的（{@code From} 必须为可投递的真实地址），任何客户端均如此。
 * 如需隐藏个人邮箱，只能更换发件账号本身。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailCodeSender implements CodeSender {

    /** 收件箱列表展示的发件人名称（缺省时仅显示裸地址） */
    private static final String SENDER_NAME = "灵阅";

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String from;

    @Override
    public void send(String email, String code) {
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from, SENDER_NAME);
            helper.setTo(email);
            helper.setSubject("【灵阅】邮箱验证码");
            helper.setText("您的验证码是：" + code + "，5 分钟内有效。若非本人操作，请忽略本邮件。");
            mailSender.send(msg);
            log.info("邮箱验证码已发送至 {}", email);
        } catch (MessagingException | UnsupportedEncodingException e) {
            // 包装为 Spring 的 MailSendException：与 SimpleMailMessage 发送失败时的异常语义一致，
            // 调用方（VerifyCodeService）以 catch (Exception) 统一收口，不会遗漏。
            throw new MailSendException("验证码邮件发送失败: " + e.getMessage(), e);
        }
    }
}
