package com.example.batteryrisk.service.notification;

/** 알림 발송 실패를 나타냅니다. NotificationService가 잡아서 notification_log에 FAILED로 기록합니다. */
public class NotificationDeliveryException extends RuntimeException {
    public NotificationDeliveryException(String message) {
        super(message);
    }

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
