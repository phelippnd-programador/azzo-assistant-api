-- ─── Adiciona colunas de tokens e safety_net em llm_usage_daily ───────────────
-- tokens_used: total de tokens consumidos no dia (prompt + completion)
-- safety_net_count: número de vezes que o safety net foi acionado no dia
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE llm_usage_daily
    ADD COLUMN IF NOT EXISTS tokens_used      BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS safety_net_count INT    NOT NULL DEFAULT 0;
