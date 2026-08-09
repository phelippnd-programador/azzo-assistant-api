package br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums;

/** Resultado de uma chamada individual a um provedor, registrado no histórico de uso. */
public enum UsageStatus {
  SUCESSO,
  ERRO,
  RATE_LIMITED,
  TIMEOUT,
  FALLBACK
}
