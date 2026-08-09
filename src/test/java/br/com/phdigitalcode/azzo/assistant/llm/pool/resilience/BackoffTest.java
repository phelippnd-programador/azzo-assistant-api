package br.com.phdigitalcode.azzo.assistant.llm.pool.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;

class BackoffTest {

  /** Jitter zero: delay = base * 2^(tentativa-1), limitado ao cap. */
  private final RandomGenerator semJitter = new RandomGenerator() {
    @Override public long nextLong() { return 0; }
    @Override public double nextDouble() { return 0.0; }
  };

  @Test
  void crescimentoExponencialSemJitter() {
    assertEquals(200, Backoff.delayMs(1, 200, 5000, 0.3, semJitter));
    assertEquals(400, Backoff.delayMs(2, 200, 5000, 0.3, semJitter));
    assertEquals(800, Backoff.delayMs(3, 200, 5000, 0.3, semJitter));
  }

  @Test
  void respeitaOTetoCap() {
    assertEquals(2000, Backoff.delayMs(10, 200, 2000, 0.3, semJitter));
  }

  @Test
  void jitterFicaDentroDaFracao() {
    RandomGenerator jitterMax = new RandomGenerator() {
      @Override public long nextLong() { return 0; }
      @Override public double nextDouble() { return 1.0; }
    };
    // base 400 + jitter de ate 30% => 520
    long d = Backoff.delayMs(2, 200, 5000, 0.3, jitterMax);
    assertTrue(d >= 400 && d <= 520, "delay dentro do intervalo esperado: " + d);
  }
}
