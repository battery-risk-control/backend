package com.example.batteryrisk.service.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** AWS SES(SMTP 인터페이스) 또는 일반 SMTP 서버로 이메일을 발송합니다. */
@Component
public class EmailNotificationChannel implements NotificationChannel {
    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final boolean configured;

    public EmailNotificationChannel(
            JavaMailSender mailSender,
            @Value("${app.notification.mail-from:}") String mailFrom,
            @Value("${spring.mail.host:}") String mailHost) {
        this.mailSender = mailSender;
        this.mailFrom = mailFrom;
        this.configured = StringUtils.hasText(mailHost) && StringUtils.hasText(mailFrom);
    }

    @Override
    public String name() {
        return "EMAIL";
    }

    @Override
    public void send(String recipient, String subject, String body) {
        if (!configured) {
            throw new NotificationDeliveryException("이메일 발송이 설정되지 않았습니다 (spring.mail.host/app.notification.mail-from).");
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(recipient);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (MailException exception) {
            throw new NotificationDeliveryException("이메일 발송 실패: " + exception.getMessage(), exception);
        }
    }
}
