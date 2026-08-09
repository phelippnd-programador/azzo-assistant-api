-- Limites de tokens por minuto/dia no nivel do MODELO (free tier dos provedores
-- costuma documentar RPM/RPD/TPM/TPD por modelo). Os limites de requisicoes por
-- minuto/dia ja existiam em llm_model; aqui completamos com os de tokens.
ALTER TABLE llm_model
  ADD COLUMN IF NOT EXISTS limite_tokens_minuto BIGINT,
  ADD COLUMN IF NOT EXISTS limite_tokens_dia    BIGINT;
