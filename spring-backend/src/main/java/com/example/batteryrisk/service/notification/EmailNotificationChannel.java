package com.example.batteryrisk.service.notification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EmailNotificationChannel
        implements NotificationChannel {

    private final JavaMailSender mailSender;
    private final String mailFrom;
    private final boolean configured;

    public EmailNotificationChannel(
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${app.notification.mail-from:}")
            String mailFrom,
            @Value("${spring.mail.host:}")
            String mailHost
    ) {
        this.mailSender =
                mailSenderProvider.getIfAvailable();
        this.mailFrom = mailFrom;
        this.configured =
                this.mailSender != null
                        && StringUtils.hasText(mailHost)
                        && StringUtils.hasText(mailFrom);
    }

    @Override
    public String name() {
        return "EMAIL";
    }

    @Override
    public void send(
            String recipient,
            String subject,
            String body
    ) {
        if (!configured) {
            throw new NotificationDeliveryException(
                    "이메일 발송 설정이 없습니다."
            );
        }

        try {
            SimpleMailMessage message =
                    new SimpleMailMessage();

            message.setFrom(mailFrom);
            message.setTo(recipient);
            message.setSubject(subject);
            message.setText(body);

            mailSender.send(message);
        } catch (MailException exception) {
            throw new NotificationDeliveryException(
                    "이메일 발송 실패: "
                            + exception.getMessage(),
                    exception
            );
        }
    }
}