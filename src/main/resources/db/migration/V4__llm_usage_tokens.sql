-- ─── Tabela de uso de tokens por chamada LLM ──────────────────────────────────
-- Persiste o consumo de tokens (prompt + completion) por requisição ao LLM.
-- Permite auditoria granular de custo por tenant/provider/conversa.
-- Complementa llm_usage_daily (V2) que agrega por dia.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS llm_usage_tokens (
    id                UUID        NOT NULL,
    tenant_id         UUID        NOT NULL,
    user_identifier   VARCHAR(255) NOT NULL,
    provider          VARCHAR(20) NOT NULL,
    model             VARCHAR(100),
    prompt_tokens     INT         NOT NULL DEFAULT 0,
    completion_tokens INT         NOT NULL DEFAULT 0,
    total_tokens      INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_llm_usage_tokens          PRIMARY KEY (id),
    CONSTRAINT chk_llm_token_provider       CHECK (provider IN ('GROQ', 'OLLAMA')),
    CONSTRAINT chk_llm_prompt_tokens        CHECK (prompt_tokens >= 0),
    CONSTRAINT chk_llm_completion_tokens    CHECK (completion_tokens >= 0),
    CONSTRAINT chk_llm_total_tokens         CHECK (total_tokens >= 0)
);

CREATE INDEX IF NOT EXISTS idx_llm_usage_tokens_tenant
    ON llm_usage_tokens (tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_llm_usage_tokens_provider_date
    ON llm_usage_tokens (provider, created_at DESC);
