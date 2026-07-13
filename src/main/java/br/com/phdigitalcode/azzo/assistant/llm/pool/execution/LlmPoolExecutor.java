package br.com.phdigitalcode.azzo.assistant.llm.pool.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import br.com.phdigitalcode.azzo.assistant.llm.pool.adapter.AdapterCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.adapter.LlmAdapterRegistry;
import br.com.phdigitalcode.azzo.assistant.llm.pool.adapter.LlmProviderAdapter;
import br.com.phdigitalcode.azzo.assistant.llm.pool.adapter.LlmProviderException;
import br.com.phdigitalcode.azzo.assistant.llm.pool.consumption.ConsumptionService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.cost.CostCalculator;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmModel;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmProvider;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.UsageStatus;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmResponse;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.TokenUsage;
import br.com.phdigitalcode.azzo.assistant.llm.pool.resilience.Backoff;
import br.com.phdigitalcode.azzo.assistant.llm.pool.resilience.CredentialCircuitBreaker;
import br.com.phdigitalcode.azzo.assistant.llm.pool.routing.LlmRoutingService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.routing.RoutingRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.routing.RoutingSelection;
import br.com.phdigitalcode.azzo.assistant.llm.pool.usage.LlmUsageRecordingService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.usage.UsageEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Executor do pool: pega a ordem de seleção do roteador e tenta cada opção com
 * retry/backoff, circuit breaker e a cadeia de fallback (outra chave → outro modelo →
 * outro provedor). Contabiliza tokens/custo/latência de cada tentativa. Uma falha de
 * provedor nunca interrompe o atendimento — no pior caso retorna resposta de erro
 * controlada e o chamador decide (ex.: fallback legado).
 */
@ApplicationScoped
public class LlmPoolExecutor {

  private static final Logger LOG = Logger.getLogger(LlmPoolExecutor.class);

  @Inject LlmRoutingService routingService;
  @Inject LlmAdapterRegistry registry;
  @Inject AdapterCredentialFactory credentialFactory;
  @Inject ConsumptionService consumptionService;
  @Inject LlmUsageRecordingService recordingService;
  @Inject CostCalculator costCalculator;
  @Inject CredentialCircuitBreaker circuitBreaker;
  @Inject CredentialBlockingService blockingService;
  @Inject br.com.phdigitalcode.azzo.assistant.llm.pool.observability.PoolMetrics metrics;

  @ConfigProperty(name = "assistant.llm.pool.rate-limit-block-ms", defaultValue = "60000")
  long defaultBlockMs;

  /** Executa a chamada pelo pool. {@link LlmResponse#erro()} quando nada foi possível. */
  public LlmResponse executar(LlmRequest req) {
    metrics.inicioChamada();
    try {
      return executarInterno(req);
    } finally {
      metrics.fimChamada();
    }
  }

  private LlmResponse executarInterno(LlmRequest req) {
    RoutingRequest rr = montarRoutingRequest(req);
    List<RoutingSelection> selecoes = routingService.selecionar(rr);
    if (selecoes.isEmpty()) {
      LOG.warn("[PoolExecutor] Sem opção elegível no pool");
      return LlmResponse.falha();
    }

    boolean fallback = false;
    int tentativaGlobal = 0;

    for (RoutingSelection sel : selecoes) {
      UUID credId = sel.credential().id;
      if (circuitBreaker.isOpen(credId)) {
        LOG.debugf("[PoolExecutor] CB aberto para credential=%s — pulando", credId);
        metrics.circuitSkip(sel.provider().nome);
        continue;
      }
      req.modelo = sel.model().nomeModelo;
      AdapterCredential ac = credentialFactory.build(sel.provider(), sel.credential());
      LlmProviderAdapter adapter = registry.resolver(sel.provider().tipo);
      int maxTentativas = Math.max(1, sel.provider().quantidadeMaximaTentativas);

      for (int tentativa = 1; tentativa <= maxTentativas; tentativa++) {
        tentativaGlobal++;
        Instant inicio = Instant.now();
        long t0 = System.currentTimeMillis();
        try {
          LlmResponse resp = adapter.enviar(req, ac);
          long latencia = System.currentTimeMillis() - t0;
          registrarSucesso(sel, req, resp, latencia, tentativaGlobal, fallback, inicio);
          circuitBreaker.onSuccess(credId);
          if (fallback) LOG.infof("[PoolExecutor] Fallback bem-sucedido em provider=%s", sel.provider().nome);
          return resp;
        } catch (LlmProviderException e) {
          long latencia = System.currentTimeMillis() - t0;
          registrarFalha(sel, req, e.getHttpStatus(), statusDe(e), tipoErro(e), e.getMessage(),
              latencia, tentativaGlobal, fallback, inicio);

          if (e.isRateLimited()) {
            long blockMs = e.getRetryAfterMs() != null ? e.getRetryAfterMs() : defaultBlockMs;
            blockingService.bloquear(credId, Instant.now().plusMillis(blockMs));
            circuitBreaker.onFailure(credId);
            break; // não retenta a mesma chave após 429
          }
          if (e.isAuthError() || (e.getHttpStatus() >= 400 && e.getHttpStatus() < 500)) {
            circuitBreaker.onFailure(credId);
            break; // erro funcional: não retenta a mesma chave
          }
          if (tentativa < maxTentativas) {
            dormirBackoff(tentativa);
            continue; // 5xx: transiente
          }
          circuitBreaker.onFailure(credId);
        } catch (Exception e) {
          long latencia = System.currentTimeMillis() - t0;
          registrarFalha(sel, req, null, UsageStatus.TIMEOUT, "CONEXAO", e.getMessage(),
              latencia, tentativaGlobal, fallback, inicio);
          if (tentativa < maxTentativas) {
            dormirBackoff(tentativa);
            continue;
          }
          circuitBreaker.onFailure(credId);
        }
      }
      fallback = true; // próximas opções contam como fallback
    }

    LOG.warn("[PoolExecutor] Todas as opções falharam");
    return LlmResponse.falha();
  }

  // ─── Registro ─────────────────────────────────────────────────────────────

  private void registrarSucesso(RoutingSelection sel, LlmRequest req, LlmResponse resp,
      long latencia, int tentativa, boolean fallback, Instant inicio) {
    LlmModel model = sel.model();
    TokenUsage uso = resp.uso() != null ? resp.uso() : TokenUsage.vazio();
    CostCalculator.CostResult custo = costCalculator.calcular(model, uso);

    consumptionService.registrar(sel.credential().id, uso.total(), custo.custo(), Instant.now());

    UsageEvent e = baseEvent(sel, req, tentativa, fallback, inicio);
    e.tokens = uso;
    e.custoEstimado = custo.custo();
    e.franquiaGratuita = custo.franquiaGratuita();
    e.latenciaMs = (int) latencia;
    e.status = UsageStatus.SUCESSO;
    e.dataResposta = Instant.now();
    recordingService.registrar(e);

    metrics.chamada(sel.provider().nome, model.nomeModelo, "SUCESSO", latencia,
        uso.entrada(), uso.saida(), custo.custo(), fallback);
  }

  private void registrarFalha(RoutingSelection sel, LlmRequest req, Integer http, UsageStatus status,
      String tipoErro, String msg, long latencia, int tentativa, boolean fallback, Instant inicio) {
    UsageEvent e = baseEvent(sel, req, tentativa, fallback, inicio);
    e.latenciaMs = (int) latencia;
    e.status = status;
    e.codigoHttp = http;
    e.tipoErro = tipoErro;
    e.mensagemErroResumida = msg; // já sanitizada (sem chave) na origem
    e.dataResposta = Instant.now();
    recordingService.registrar(e);

    String provider = sel.provider().nome;
    metrics.chamada(provider, sel.model().nomeModelo, status.name(), latencia, 0, 0, null, fallback);
    if (status == UsageStatus.RATE_LIMITED) metrics.rateLimit(provider);
    if (status == UsageStatus.TIMEOUT) metrics.timeout(provider);
  }

  private UsageEvent baseEvent(RoutingSelection sel, LlmRequest req, int tentativa, boolean fallback, Instant inicio) {
    UsageEvent e = new UsageEvent();
    e.tenantId = parseUuid(req.tenantId);
    e.conversaId = req.conversaId;
    e.mensagemId = req.mensagemId;
    e.providerId = sel.provider().id;
    e.credentialId = sel.credential().id;
    e.modelId = sel.model().id;
    e.tentativa = tentativa;
    e.fallbackUtilizado = fallback;
    e.dataRequisicao = inicio;
    return e;
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private RoutingRequest montarRoutingRequest(LlmRequest req) {
    RoutingRequest rr = new RoutingRequest();
    rr.tokensEntradaEstimados = estimarEntrada(req);
    rr.tokensSaidaEstimados = req.maxTokens != null ? req.maxTokens : 256;
    rr.exigeJsonMode = req.jsonMode;
    rr.exigeToolCalling = req.toolCalling;
    rr.tenantId = req.tenantId;
    return rr;
  }

  private int estimarEntrada(LlmRequest req) {
    int chars = 0;
    if (req.systemPrompt != null) chars += req.systemPrompt.length();
    if (req.historico != null) {
      for (var m : req.historico) {
        if (m.content() != null) chars += m.content().length();
      }
    }
    if (req.mensagemAtual != null) chars += req.mensagemAtual.length();
    return Math.max(1, chars / 4);
  }

  private UsageStatus statusDe(LlmProviderException e) {
    return e.isRateLimited() ? UsageStatus.RATE_LIMITED : UsageStatus.ERRO;
  }

  private String tipoErro(LlmProviderException e) {
    if (e.isRateLimited()) return "RATE_LIMIT";
    if (e.isAuthError()) return "AUTENTICACAO";
    if (e.isServerError()) return "SERVIDOR";
    return "HTTP_" + e.getHttpStatus();
  }

  private void dormirBackoff(int tentativa) {
    long ms = Backoff.delayMs(tentativa, 200, 2000, 0.3, ThreadLocalRandom.current());
    try {
      Thread.sleep(ms);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }

  private UUID parseUuid(String v) {
    if (v == null || v.isBlank()) return null;
    try {
      return UUID.fromString(v);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
