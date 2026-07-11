package br.com.phdigitalcode.azzo.assistant.llm.pool.routing;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.RoutingStrategy;

/** Requisitos que o roteador usa para filtrar e pontuar candidatos. */
public class RoutingRequest {

  /** Estratégia a aplicar; se nula, usa o padrão configurado. */
  public RoutingStrategy estrategia;

  /** Estimativa de tokens de entrada (para checar janela de contexto e custo). */
  public int tokensEntradaEstimados = 0;

  /** Estimativa de tokens de saída (para custo). */
  public int tokensSaidaEstimados = 0;

  public boolean exigeJsonMode = false;
  public boolean exigeToolCalling = false;

  /** Preferir modelos marcados como indicados para atendimento. */
  public boolean preferirAtendimento = true;

  public String tenantId;

  public RoutingRequest() {}
}
