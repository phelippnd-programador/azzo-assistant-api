-- Suporte administrativo ao pool de provedores.

-- Mascara da chave (ex.: gsk_****4F8A) calculada na gravacao. Permite exibir a
-- credencial sem NUNCA decriptar a chave so para mostrar (seguranca, secao 6).
ALTER TABLE llm_credential
  ADD COLUMN IF NOT EXISTS api_key_mascara VARCHAR(80) NOT NULL DEFAULT '';

-- Data da ultima sincronizacao de modelos do provedor (secao 17).
ALTER TABLE llm_provider
  ADD COLUMN IF NOT EXISTS ultima_sincronizacao_modelos TIMESTAMPTZ;

-- Auditoria de operacoes administrativas (secao 21). NUNCA guarda a chave.
CREATE TABLE IF NOT EXISTS llm_admin_audit (
    id                UUID         NOT NULL,
    usuario           VARCHAR(160),
    operacao          VARCHAR(60)  NOT NULL,
    provider_id       UUID,
    credential_id     UUID,
    identificacao     VARCHAR(200),
    -- Campos alterados SEM conteudo secreto (JSON).
    detalhes          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    data_operacao     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_llm_admin_audit PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_llm_admin_audit_data ON llm_admin_audit (data_operacao DESC);
CREATE INDEX IF NOT EXISTS idx_llm_admin_audit_op   ON llm_admin_audit (operacao);
