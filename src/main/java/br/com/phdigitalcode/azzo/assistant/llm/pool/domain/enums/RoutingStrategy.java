package br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums;

/** Estratégia de seleção de provedor/credencial/modelo no roteamento. */
public enum RoutingStrategy {
  /** Prioriza o menor custo estimado por chamada. */
  MENOR_CUSTO,
  /** Prioriza a menor latência média recente. */
  MENOR_LATENCIA,
  /** Combina disponibilidade, gratuidade, latência, limite e custo (padrão). */
  BALANCEADO,
  /** Segue estritamente a prioridade administrativa configurada. */
  PRIORIDADE_FIXA,
  /** Distribui a carga entre credenciais saudáveis por peso. */
  ROUND_ROBIN_PONDERADO,
  /** Esgota as opções gratuitas antes de recorrer às pagas. */
  GRATUITO_PRIMEIRO;

  public static final RoutingStrategy PADRAO = BALANCEADO;
}
