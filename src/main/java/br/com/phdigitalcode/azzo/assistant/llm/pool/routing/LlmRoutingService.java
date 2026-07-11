package br.com.phdigitalcode.azzo.assistant.llm.pool.routing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import br.com.phdigitalcode.azzo.assistant.llm.pool.consumption.ConsumptionService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.cost.CostCalculator;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmModel;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmProvider;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.RoutingStrategy;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Serviço central de roteamento (seções 7 e 8). Monta os candidatos elegíveis
 * (provedor + credencial + modelo), eliminando indisponíveis/incompatíveis/sem limite,
 * e os ordena pela estratégia via {@link LlmRoutingScorer}. Retorna a opção primária
 * seguida da ordem de fallback.
 */
@ApplicationScoped
public class LlmRoutingService {

  private static final Logger LOG = Logger.getLogger(LlmRoutingService.class);
  private static final int JANELA_METRICAS_MIN = 15;

  @Inject LlmRoutingScorer scorer;
  @Inject ConsumptionService consumptionService;
  @Inject RoutingMetricsService metricsService;
  @Inject CostCalculator costCalculator;

  @ConfigProperty(name = "assistant.llm.pool.default-strategy", defaultValue = "BALANCEADO")
  String estrategiaPadraoConfig;

  /** Ordena as opções elegíveis da melhor para a pior. Vazio = pool sem capacidade agora. */
  public List<RoutingSelection> selecionar(RoutingRequest request) {
    Instant agora = Instant.now();
    Instant desdeMetricas = agora.minus(JANELA_METRICAS_MIN, ChronoUnit.MINUTES);
    RoutingStrategy estrategia = resolverEstrategia(request);
    long tokensEstimados = (long) request.tokensEntradaEstimados + request.tokensSaidaEstimados;

    List<Scored> avaliados = new ArrayList<>();

    List<LlmProvider> provedores = LlmProvider.list("ativo", true);
    for (LlmProvider provider : provedores) {
      List<LlmCredential> credenciais = LlmCredential.list(
          "providerId = ?1 and ativo = true and removida = false", provider.id);
      if (credenciais.isEmpty()) continue;

      List<LlmModel> modelos = LlmModel.list(
          "providerId = ?1 and ativo = true and descontinuado = false", provider.id);
      if (modelos.isEmpty()) continue;

      for (LlmModel model : modelos) {
        if (!compativel(model, request)) continue;

        for (LlmCredential credential : credenciais) {
          if (!credential.elegivel(agora)) continue;
          if (!consumptionService.temCapacidade(credential, model, tokensEstimados, agora)) continue;

          RoutingCandidate cand = montarCandidato(provider, credential, model, request, desdeMetricas, agora);
          avaliados.add(new Scored(cand, new RoutingSelection(provider, credential, model),
              scorer.score(cand, estrategia)));
        }
      }
    }

    if (avaliados.isEmpty()) {
      LOG.warnf("[Routing] Nenhuma opção elegível (estrategia=%s, providers=%d)", estrategia, provedores.size());
      return List.of();
    }

    return avaliados.stream()
        .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed())
        .map(s -> s.selecao)
        .toList();
  }

  private RoutingStrategy resolverEstrategia(RoutingRequest request) {
    if (request != null && request.estrategia != null) return request.estrategia;
    try {
      return RoutingStrategy.valueOf(estrategiaPadraoConfig);
    } catch (IllegalArgumentException e) {
      return RoutingStrategy.PADRAO;
    }
  }

  private boolean compativel(LlmModel model, RoutingRequest req) {
    if (req.exigeJsonMode && !model.suportaJsonMode) return false;
    if (req.exigeToolCalling && !model.suportaToolCalling) return false;
    // Janela de contexto insuficiente para o prompt estimado (seção 9).
    return model.contextWindow == null || req.tokensEntradaEstimados <= model.contextWindow;
  }

  private RoutingCandidate montarCandidato(LlmProvider provider, LlmCredential credential, LlmModel model,
      RoutingRequest req, Instant desdeMetricas, Instant agora) {
    RoutingCandidate c = new RoutingCandidate();
    c.providerId = provider.id;
    c.credentialId = credential.id;
    c.modelId = model.id;
    c.tipo = provider.tipo;
    c.modeloNome = model.nomeModelo;
    c.prioridade = (provider.prioridade + credential.prioridade + model.prioridade) / 3;
    c.peso = credential.peso;
    c.gratuito = model.gratuito;

    TokenUsage estimado = TokenUsage.estimado(req.tokensEntradaEstimados, req.tokensSaidaEstimados);
    c.custoEstimado = costCalculator.calcular(model, estimado).custo();

    RoutingMetricsService.Metrics m = metricsService.recentes(credential.id, desdeMetricas);
    c.latenciaMediaMs = m.latenciaMediaMs();
    c.taxaErro = m.taxaErro();
    c.saturacao = consumptionService.saturacao(credential, agora);
    return c;
  }

  private record Scored(RoutingCandidate candidato, RoutingSelection selecao, double score) {}
}
