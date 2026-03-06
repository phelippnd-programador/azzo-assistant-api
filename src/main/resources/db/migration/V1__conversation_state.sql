CREATE TABLE IF NOT EXISTS conversation_state (
    id          UUID         NOT NULL,
    tenant_id   UUID         NOT NULL,
    user_identifier VARCHAR(255) NOT NULL,
    state_json  TEXT         NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_conversation_state PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_conversation_state_tenant_user
    ON conversation_state (tenant_id, user_identifier);

CREATE INDEX IF NOT EXISTS idx_conversation_state_updated_at
    ON conversation_state (updated_at);
