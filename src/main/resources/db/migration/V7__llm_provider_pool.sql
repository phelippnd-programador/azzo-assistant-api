-- ─── Pool de provedores de LLM ───────────────────────────────────────────────
-- Modelo de dados do gerenciamento, seleção e balanceamento de provedores de IA.
-- Todas as API keys sao gravadas CRIPTOGRAFADAS (AES/GCM) na coluna
-- api_key_criptografada — nunca em texto puro.
-- ─────────────────────────────────────────────────────────────────────────────

-- 5.1 Provedor de LLM
CREATE TABLE IF NOT EXISTS llm_provider (
    id                          UUID         NOT NULL,
    nome                        VARCHAR(120) NOT NULL,
    tipo                        VARCHAR(40)  NOT NULL,
    url_base                    VARCHAR(500),
    ativo                       BOOLEAN      NOT NULL DEFAULT TRUE,
    prioridade                  INT          NOT NULL DEFAULT 100,
    ordem_fallback              INT          NOT NULL DEFAULT 100,
    -- Configuracao do adaptador OpenAI-compatible (endpoint/headers/auth) em JSON.
    config_adaptador            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    timeout_conexao_ms          INT          NOT NULL DEFAULT 5000,
    timeout_resposta_ms         INT          NOT NULL DEFAULT 30000,
    quantidade_maxima_tentativas INT         NOT NULL DEFAULT 2,
    suporta_streaming           BOOLEAN      NOT NULL DEFAULT FALSE,
    suporta_tool_calling        BOOLEAN      NOT NULL DEFAULT FALSE,
    suporta_json_mode           BOOLEAN      NOT NULL DEFAULT FALSE,
    data_criacao                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    data_atualizacao            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_llm_provider PRIMARY KEY (id),
    CONSTRAINT uk_llm_provider_nome UNIQUE (nome)
);
CREATE INDEX IF NOT EXISTS idx_llm_provider_ativo ON llm_provider (ativo);

-- 5.2 Credencial do provedor (multiplas chaves por provedor)
CREATE TABLE IF NOT EXISTS llm_credential (
    id                        UUID         NOT NULL,
    provider_id               UUID         NOT NULL,
    nome_identificacao        VARCHAR(120) NOT NULL,
    api_key_criptografada     TEXT         NOT NULL,
    organizacao               VARCHAR(200),
    projeto                   VARCHAR(200),
    ativo                     BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Exclusao logica: preserva o historico de utilizacao.
    removida                  BOOLEAN      NOT NULL DEFAULT FALSE,
    peso                      INT          NOT NULL DEFAULT 100,
    prioridade                INT          NOT NULL DEFAULT 100,
    limite_requisicoes_minuto INT,
    limite_requisicoes_dia    INT,
    limite_tokens_minuto      BIGINT,
    limite_tokens_dia         BIGINT,
    limite_tokens_mes         BIGINT,
    limite_custo_mensal       NUMERIC(12,2),
    total_tokens_entrada      BIGINT       NOT NULL DEFAULT 0,
    total_tokens_saida        BIGINT       NOT NULL DEFAULT 0,
    total_requisicoes         BIGINT       NOT NULL DEFAULT 0,
    total_erros               BIGINT       NOT NULL DEFAULT 0,
    ultimo_uso                TIMESTAMPTZ,
    bloqueada_ate             TIMESTAMPTZ,
    data_criacao              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    data_atualizacao          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_llm_credential PRIMARY KEY (id),
    CONSTRAINT fk_llm_credential_provider FOREIGN KEY (provider_id) REFERENCES llm_provider (id)
);
CREATE INDEX IF NOT EXISTS idx_llm_credential_provider ON llm_credential (provider_id);
CREATE INDEX IF NOT EXISTS idx_llm_credential_ativa ON llm_credential (ativo, removida);

-- 5.3 Modelos disponiveis por provedor
CREATE TABLE IF NOT EXISTS llm_model (
    id                        UUID         NOT NULL,
    provider_id               UUID         NOT NULL,
    nome_modelo               VARCHAR(200) NOT NULL,
    nome_exibicao             VARCHAR(200),
    ativo                     BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Marca modelo que deixou de existir na sincronizacao, sem apagar config.
    descontinuado             BOOLEAN      NOT NULL DEFAULT FALSE,
    prioridade                INT          NOT NULL DEFAULT 100,
    context_window            INT,
    max_output_tokens         INT,
    custo_input_por_milhao    NUMERIC(12,6) NOT NULL DEFAULT 0,
    custo_output_por_milhao   NUMERIC(12,6) NOT NULL DEFAULT 0,
    custo_fixo_por_chamada    NUMERIC(12,6) NOT NULL DEFAULT 0,
    moeda                     VARCHAR(3)   NOT NULL DEFAULT 'USD',
    gratuito                  BOOLEAN      NOT NULL DEFAULT FALSE,
    indicado_para_atendimento BOOLEAN      NOT NULL DEFAULT FALSE,
    suporta_streaming         BOOLEAN      NOT NULL DEFAULT FALSE,
    suporta_tool_calling      BOOLEAN      NOT NULL DEFAULT FALSE,
    suporta_json_mode         BOOLEAN      NOT NULL DEFAULT FALSE,
    limite_requisicoes_minuto INT,
    limite_requisicoes_dia    INT,
    data_criacao              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    data_atualizacao          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_llm_model PRIMARY KEY (id),
    CONSTRAINT fk_llm_model_provider FOREIGN KEY (provider_id) REFERENCES llm_provider (id),
    CONSTRAINT uk_llm_model_provider_nome UNIQUE (provider_id, nome_modelo)
);
CREATE INDEX IF NOT EXISTS idx_llm_model_provider ON llm_model (provider_id);
CREATE INDEX IF NOT EXISTS idx_llm_model_ativo ON llm_model (ativo, descontinuado);

-- 5.4 Historico de utilizacao (uma linha por chamada; sem prompts/respostas completos)
CREATE TABLE IF NOT EXISTS llm_usage_history (
    id                     UUID         NOT NULL,
    tenant_id              UUID,
    conversa_id            VARCHAR(120),
    mensagem_id            VARCHAR(120),
    provider_id            UUID,
    credential_id          UUID,
    model_id               UUID,
    tokens_entrada         INT          NOT NULL DEFAULT 0,
    tokens_saida           INT          NOT NULL DEFAULT 0,
    tokens_total           INT          NOT NULL DEFAULT 0,
    tokens_estimados       BOOLEAN      NOT NULL DEFAULT FALSE,
    custo_estimado         NUMERIC(14,8) NOT NULL DEFAULT 0,
    franquia_gratuita      BOOLEAN      NOT NULL DEFAULT FALSE,
    latencia_ms            INT,
    status                 VARCHAR(20)  NOT NULL,
    codigo_http            INT,
    tipo_erro              VARCHAR(60),
    mensagem_erro_resumida VARCHAR(300),
    tentativa              INT          NOT NULL DEFAULT 1,
    fallback_utilizado     BOOLEAN      NOT NULL DEFAULT FALSE,
    data_requisicao        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    data_resposta          TIMESTAMPTZ,
    CONSTRAINT pk_llm_usage_history PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_llm_usage_hist_data     ON llm_usage_history (data_requisicao DESC);
CREATE INDEX IF NOT EXISTS idx_llm_usage_hist_provider ON llm_usage_history (provider_id);
CREATE INDEX IF NOT EXISTS idx_llm_usage_hist_cred     ON llm_usage_history (credential_id);
CREATE INDEX IF NOT EXISTS idx_llm_usage_hist_tenant   ON llm_usage_history (tenant_id);
CREATE INDEX IF NOT EXISTS idx_llm_usage_hist_status   ON llm_usage_history (status);

-- 5.5 Estado de consumo por janela (minuto/hora/dia/mes) e credencial.
-- Upsert atomico (INSERT ... ON CONFLICT DO UPDATE) garante contagem correta
-- com multiplas instancias da aplicacao, sem depender de contadores em memoria.
CREATE TABLE IF NOT EXISTS llm_consumption_bucket (
    id             UUID         NOT NULL,
    credential_id  UUID         NOT NULL,
    janela         VARCHAR(10)  NOT NULL,          -- MINUTO | HORA | DIA | MES
    janela_inicio  TIMESTAMPTZ  NOT NULL,          -- inicio truncado da janela (UTC)
    requisicoes    INT          NOT NULL DEFAULT 0,
    tokens         BIGINT       NOT NULL DEFAULT 0,
    custo          NUMERIC(14,8) NOT NULL DEFAULT 0,
    data_criacao   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    data_atualizacao TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_llm_consumption_bucket PRIMARY KEY (id),
    CONSTRAINT fk_llm_bucket_credential FOREIGN KEY (credential_id) REFERENCES llm_credential (id),
    CONSTRAINT uk_llm_bucket UNIQUE (credential_id, janela, janela_inicio),
    CONSTRAINT chk_llm_bucket_janela CHECK (janela IN ('MINUTO', 'HORA', 'DIA', 'MES'))
);
CREATE INDEX IF NOT EXISTS idx_llm_bucket_cred_janela ON llm_consumption_bucket (credential_id, janela, janela_inicio DESC);
