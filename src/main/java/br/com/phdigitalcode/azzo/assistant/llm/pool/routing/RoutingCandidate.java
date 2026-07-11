package br.com.phdigitalcode.azzo.assistant.llm.pool.routing;

import java.math.BigDecimal;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.LlmProviderType;

/**
 * Uma opção elegível (provedor + credencial + modelo) já filtrada por
 * disponibilidade/limite/capacidades, pronta para ser pontuada pelo
 * {@link LlmRoutingScorer}. Carrega apenas os sinais usados no score.
 */
public class RoutingCandidate {

  public UUID providerId;
  public UUID credentialId;
  public UUID modelId;
  public LlmProviderType tipo;
  public String modeloNome;

  /** Prioridade administrativa efetiva (menor = mais prioritário). */
  public int prioridade = 100;

  /** Peso para distribuição no round-robin ponderado. */
  public int peso = 100;

  public boolean gratuito = false;

  /** Custo estimado da chamada (na moeda do modelo). Zero para gratuitos. */
  public BigDecimal custoEstimado = BigDecimal.ZERO;

  /** Latência média recente (ms). 0 quando ainda não há histórico. */
  public double latenciaMediaMs = 0.0;

  /** Taxa de erro recente (0..1). */
  public double taxaErro = 0.0;

  /** Saturação da janela mais restritiva (0..1+); quanto menor, mais folga. */
  public double saturacao = 0.0;

  public RoutingCandidate() {}
}
