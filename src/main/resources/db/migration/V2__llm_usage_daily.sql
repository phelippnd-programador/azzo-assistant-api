-- ─── Tabela de uso diário de LLM ─────────────────────────────────────────────
-- Persiste o contador de requisições por provider (GROQ | OLLAMA) por dia.
-- Sobrevive a reinicializações — evita estourar o free tier do Groq sem saber.
-- Consultável pelo endpoint GET /api/v1/assistant/admin/metrics
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS llm_usage_daily (
    usage_date    DATE        NOT NULL,
    provider      VARCHAR(20) NOT NULL,
    request_count INT         NOT NULL DEFAULT 0,

    CONSTRAINT pk_llm_usage_daily PRIMARY KEY (usage_date, provider),
    CONSTRAINT chk_provider       CHECK (provider IN ('GROQ', 'OLLAMA')),
    CONSTRAINT chk_count_positive CHECK (request_count >= 0)
);

-- Índice para consultas por data (relatórios e métricas)
CREATE INDEX IF NOT EXISTS idx_llm_usage_daily_date
    ON llm_usage_daily (usage_date DESC);
