CREATE TABLE revoked_token_sessions (
    session_id  VARCHAR(36) PRIMARY KEY,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_revoked_token_sessions_expires_at
    ON revoked_token_sessions (expires_at);
