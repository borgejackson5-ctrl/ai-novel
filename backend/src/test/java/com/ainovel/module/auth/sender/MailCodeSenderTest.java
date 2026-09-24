package com.ainovel.module.auth.sender;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 约束「收件箱中显示的是『灵阅』而不是裸地址」。
 *
 * <p>值得单独测试的原因：显示名一旦退回 {@code SimpleMailMessage.setFrom(String)} 的字符串写法，
 * 中文会乱码且不报任何错，只有真实发送一封邮件才能发现。
 * 这里用 mock 的 JavaMailSender 捕获 MimeMessage，直接断言解析出的 personal。
 */
@DisplayName("MailCodeSender：发件人显示名")
class MailCodeSenderTest {

    private static final String FROM = "lingyue_se2026@qq.com";

    private MimeMessage sendAndCapture() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage msg = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(msg);

        MailCodeSender sender = new MailCodeSender(mailSender);
        // from 是 @Value 字段，单测中没有 Spring 上下文，需手动设置
        ReflectionTestUtils.setField(sender, "from", FROM);

        sender.send("someone@example.com", "123456");
        verify(mailSender).send(any(MimeMessage.class));
        return msg;
    }

    @Test
    @DisplayName("发件人带中文显示名，且能被正确解析回来（不是 null、不是乱码）")
    void send_setsChinesePersonalName() throws Exception {
        MimeMessage msg = sendAndCapture();

        InternetAddress from = (InternetAddress) msg.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo(FROM);
        assertThat(from.getPersonal())
                .as("显示名为空 = 收件箱里只能看到裸地址；乱码 = 没走 UTF-8 编码")
                .isEqualTo("灵阅");
    }

    @Test
    @DisplayName("主题与正文里的验证码都在，正文按 UTF-8 声明")
    void send_keepsSubjectAndBody() throws Exception {
        MimeMessage msg = sendAndCapture();

        assertThat(msg.getSubject()).isEqualTo("【灵阅】邮箱验证码");
        assertThat(String.valueOf(msg.getContent())).contains("123456").contains("5 分钟");

        // 必须在 saveChanges() 之后再看 Content-Type：在此之前 header 尚未序列化，
        // getContentType() 只会回落成 DataHandler 的默认 text/plain（此时 header 为 null）。
        // getHeader() 返回 String[]，不要用 String.valueOf() 包装（会得到 [Ljava.lang.String;@...）。
        msg.saveChanges();
        String[] ct = msg.getHeader("Content-Type");
        assertThat(ct).as("Content-Type 头应该被写出来").isNotNull();
        assertThat(ct[0].toLowerCase())
                .as("正文没声明 UTF-8 的话，中文在部分客户端会乱码")
                .contains("utf-8");
    }
}
