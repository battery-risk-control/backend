package com.example.batteryrisk.service.notification;

/** F10 발송 채널 seam. 실제 발송 수단(이메일/Slack)을 갈아끼울 수 있도록 인터페이스로 분리합니다. */
public interface NotificationChannel {
    /** notification_log.channel에 그대로 기록되는 채널 식별자 (예: "EMAIL", "SLACK"). */
    String name();

    /** 발송 실패 시 {@link NotificationDeliveryException}을 던집니다. */
    void send(String recipient, String subject, String body);
}
