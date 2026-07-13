package br.com.phdigitalcode.azzo.assistant.llm.pool.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PoolMetricsTest {

  @Test
  void registraChamadaComTagsSeguras() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PoolMetrics metrics = new PoolMetrics(registry);

    metrics.chamada("Groq", "llama-3.1-8b", "SUCESSO", 250, 100, 40, new BigDecimal("0.0002"), false);

    assertEquals(1.0,
        registry.counter("llm.pool.calls", "provider", "Groq", "model", "llama-3.1-8b", "status", "SUCESSO").count());
    assertEquals(100.0,
        registry.counter("llm.pool.tokens", "provider", "Groq", "direction", "input").count());
    assertEquals(40.0,
        registry.counter("llm.pool.tokens", "provider", "Groq", "direction", "output").count());
    assertEquals(1, registry.timer("llm.pool.latency", "provider", "Groq", "model", "llama-3.1-8b").count());
  }

  @Test
  void contadoresDeRateLimitTimeoutFallback() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PoolMetrics metrics = new PoolMetrics(registry);

    metrics.rateLimit("Groq");
    metrics.timeout("OpenRouter");
    metrics.chamada("Cerebras", "m", "SUCESSO", 10, 0, 0, null, true);

    assertEquals(1.0, registry.counter("llm.pool.rate_limit", "provider", "Groq").count());
    assertEquals(1.0, registry.counter("llm.pool.timeout", "provider", "OpenRouter").count());
    assertEquals(1.0, registry.counter("llm.pool.fallback", "provider", "Cerebras").count());
  }

  @Test
  void inflightSobeEDesce() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    PoolMetrics metrics = new PoolMetrics(registry);

    metrics.inicioChamada();
    metrics.inicioChamada();
    assertEquals(2.0, registry.get("llm.pool.inflight").gauge().value());
    metrics.fimChamada();
    assertEquals(1.0, registry.get("llm.pool.inflight").gauge().value());
  }
}
