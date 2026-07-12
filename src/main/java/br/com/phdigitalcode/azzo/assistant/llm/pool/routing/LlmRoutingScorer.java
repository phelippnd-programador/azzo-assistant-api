package br.com.phdigitalcode.azzo.assistant.llm.pool.routing;

import java.util.Comparator;
import java.util.List;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.RoutingStrategy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Pontua e ordena candidatos de roteamento conforme a estratégia.
 *
 * <h3>Fórmula balanceada (documentada)</h3>
 * Para a estratégia {@link RoutingStrategy#BALANCEADO}, cada candidato recebe:
 * <pre>
 * score =  W_PRIORIDADE * (1 - prioridadeNorm)     // prioridade menor => score maior
 *        + W_GRATUITO   * (gratuito ? 1 : 0)        // benefício por gratuidade
 *        + W_LATENCIA   * (1 - latenciaNorm)        // benefício por baixa latência
 *        + W_LIMITE     * (1 - saturacao)           // benefício por limite disponível
 *        - W_CUSTO      * custoNorm                 // penalidade por custo
 *        - W_ERRO       * taxaErro                  // penalidade por taxa de erro
 * </pre>
 * onde cada termo é normalizado para [0..1]:
 * <ul>
 *   <li>{@code prioridadeNorm = min(1, prioridade / PRIORIDADE_REF)}</li>
 *   <li>{@code latenciaNorm   = min(1, latenciaMediaMs / LATENCIA_REF_MS)}</li>
 *   <li>{@code custoNorm      = min(1, custoEstimado / CUSTO_REF)}</li>
 *   <li>{@code saturacao, taxaErro} já em [0..1]</li>
 * </ul>
 * As demais estratégias reusam esses sinais reordenando as prioridades (custo puro,
 * latência pura, prioridade fixa, gratuito primeiro, round-robin ponderado).
 */
@ApplicationScoped
public class LlmRoutingScorer {

  // Pesos da estratégia balanceada (documentados e cobertos por teste).
  static final double W_PRIORIDADE = 30.0;
  static final double W_GRATUITO = 40.0;
  static final double W_LATENCIA = 20.0;
  static final double W_LIMITE = 15.0;
  static final double W_CUSTO = 35.0;
  static final double W_ERRO = 50.0;

  // Referências de normalização.
  static final double PRIORIDADE_REF = 200.0;
  static final double LATENCIA_REF_MS = 4000.0;
  static final double CUSTO_REF = 0.01; // custo de referência por chamada (mesma moeda do modelo)

  /** Pontuação do candidato para a estratégia. Maior score = preferível. */
  public double score(RoutingCandidate c, RoutingStrategy estrategia) {
    return switch (estrategia) {
      case BALANCEADO -> balanceado(c);
      case MENOR_CUSTO -> -custoNorm(c) * 1000 + (c.gratuito ? 1 : 0) - c.taxaErro; // custo domina
      case MENOR_LATENCIA -> -latenciaNorm(c) * 1000 - c.taxaErro;                   // latência domina
      case PRIORIDADE_FIXA -> -c.prioridade;                                          // prioridade domina
      case GRATUITO_PRIMEIRO -> (c.gratuito ? 1000 : 0) + balanceado(c);             // gratuito antes de tudo
      case ROUND_ROBIN_PONDERADO -> c.peso * (1.0 - clamp01(c.saturacao));           // peso com folga
    };
  }

  /** Ordena os candidatos do melhor (índice 0) ao pior — define primária e ordem de fallback. */
  public List<RoutingCandidate> ordenar(List<RoutingCandidate> candidatos, RoutingStrategy estrategia) {
    return candidatos.stream()
        .sorted(Comparator.comparingDouble((RoutingCandidate c) -> score(c, estrategia)).reversed())
        .toList();
  }

  private double balanceado(RoutingCandidate c) {
    double score = 0.0;
    score += W_PRIORIDADE * (1.0 - prioridadeNorm(c));
    score += W_GRATUITO * (c.gratuito ? 1.0 : 0.0);
    score += W_LATENCIA * (1.0 - latenciaNorm(c));
    score += W_LIMITE * (1.0 - clamp01(c.saturacao));
    score -= W_CUSTO * custoNorm(c);
    score -= W_ERRO * clamp01(c.taxaErro);
    return score;
  }

  private double prioridadeNorm(RoutingCandidate c) {
    return clamp01(c.prioridade / PRIORIDADE_REF);
  }

  private double latenciaNorm(RoutingCandidate c) {
    return clamp01(c.latenciaMediaMs / LATENCIA_REF_MS);
  }

  private double custoNorm(RoutingCandidate c) {
    if (c.gratuito || c.custoEstimado == null) return 0.0;
    return clamp01(c.custoEstimado.doubleValue() / CUSTO_REF);
  }

  private double clamp01(double v) {
    if (v < 0) return 0;
    return Math.min(1.0, v);
  }
}
