-- [surin 병합] F10 알림·에스컬레이션 발송 이력.
-- 출처: surin V9(create_notification_log). users.email 추가 라인은 제거(merge V7__extend_users_for_frontend_auth이 이미 추가함).
CREATE TABLE notification_log (
    id BIGSERIAL PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES analyses (analysis_id),
    channel VARCHAR(20) NOT NULL,
    recipient VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500),
    sent_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notification_analysis_channel_recipient UNIQUE (analysis_id, channel, recipient)
);
CREATE INDEX idx_notification_log_analysis ON notification_log (analysis_id);

COMMENT ON TABLE notification_log IS 'F10: CRITICAL 즉시 알림·WARNING 일일 브리핑 발송 이력(중복 발송 방지 겸용)';
