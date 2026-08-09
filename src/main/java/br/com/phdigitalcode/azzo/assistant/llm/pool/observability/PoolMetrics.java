package br.com.phdigitalcode.azzo.assistant.llm.pool.observability;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Métricas do pool compatíveis com a observabilidade existente (Micrometer/Prometheus,
 * expostas em {@code /q/metrics}). Seção 24. Só identificadores seguros nas tags —
 * nunca chave, prompt ou resposta.
 *
 * <ul>
 *   <li>{@code llm.pool.calls}{provider,model,status} — contador de chamadas</li>
 *   <li>{@code llm.pool.latency}{provider,model} — timer de latência</li>
 *   <li>{@code llm.pool.tokens}{provider,direction} — tokens de entrada/saída</li>
 *   <li>{@code llm.pool.cost}{provider} — custo estimado acumulado</li>
 *   <li>{@code llm.pool.fallback} / {@code llm.pool.rate_limit} / {@code llm.pool.timeout}</li>
 *   <li>{@code llm.pool.circuit_skip} — chamadas puladas por circuito aberto</li>
 *   <li>{@code llm.pool.inflight} — requisições em andamento (gauge)</li>
 * </ul>
 */
@ApplicationScoped
public class PoolMetrics {

  private final MeterRegistry registry;
  private final AtomicInteger emAndamento = new AtomicInteger(0);

  @Inject
  public PoolMetrics(MeterRegistry registry) {
    this.registry = registry;
    if (registry != null) {
      Gauge.builder("llm.pool.inflight", emAndamento, AtomicInteger::get)
          .description("Requisições LLM em andamento")
          .register(registry);
    }
  }

  public void inicioChamada() {
    emAndamento.incrementAndGet();
  }

  public void fimChamada() {
    emAndamento.decrementAndGet();
  }

  /** Registra o desfecho de uma tentativa (sucesso ou falha). */
  public void chamada(String provider, String model, String status, long latenciaMs,
      int tokensEntrada, int tokensSaida, BigDecimal custo, boolean fallback) {
    if (registry == null) return;
    String p = safe(provider);
    String m = safe(model);

    registry.counter("llm.pool.calls", "provider", p, "model", m, "status", safe(status)).increment();
    registry.timer("llm.pool.latency", "provider", p, "model", m).record(Duration.ofMillis(Math.max(0, latenciaMs)));

    if (tokensEntrada > 0) {
      registry.counter("llm.pool.tokens", "provider", p, "direction", "input").increment(tokensEntrada);
    }
    if (tokensSaida > 0) {
      registry.counter("llm.pool.tokens", "provider", p, "direction", "output").increment(tokensSaida);
    }
    if (custo != null && custo.signum() > 0) {
      registry.counter("llm.pool.cost", "provider", p).increment(custo.doubleValue());
    }
    if (fallback) {
      registry.counter("llm.pool.fallback", "provider", p).increment();
    }
  }

  public void rateLimit(String provider) {
    if (registry != null) registry.counter("llm.pool.rate_limit", "provider", safe(provider)).increment();
  }

  public void timeout(String provider) {
    if (registry != null) registry.counter("llm.pool.timeout", "provider", safe(provider)).increment();
  }

  public void circuitSkip(String provider) {
    if (registry != null) registry.counter("llm.pool.circuit_skip", "provider", safe(provider)).increment();
  }

  private String safe(String v) {
    return v == null || v.isBlank() ? "desconhecido" : v;
  }
}
