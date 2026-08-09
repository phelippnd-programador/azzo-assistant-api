package br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums;

/** Estado de saúde de um provedor/credencial, derivado passivamente das chamadas reais. */
public enum ProviderHealthStatus {
  /** Operando normalmente. */
  SAUDAVEL,
  /** Latência alta ou erros ocasionais — evitar quando houver alternativa. */
  DEGRADADO,
  /** Perto ou dentro de rate limit — uso restrito temporariamente. */
  LIMITADO,
  /** Circuit breaker aberto ou falhas contínuas — não usar. */
  INDISPONIVEL,
  /** Desativado administrativamente. */
  DESATIVADO
}
