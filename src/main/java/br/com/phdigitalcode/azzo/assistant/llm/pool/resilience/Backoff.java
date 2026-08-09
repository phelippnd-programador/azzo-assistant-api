package br.com.phdigitalcode.azzo.assistant.llm.pool.resilience;

import java.util.random.RandomGenerator;

/** Backoff exponencial com jitter para retentativas transientes (seção 11). */
public final class Backoff {

  private Backoff() {}

  /**
   * Atraso da retentativa {@code tentativa} (1 = primeira retentativa):
   * {@code base * 2^(tentativa-1)}, limitado a {@code cap}, mais jitter de até
   * {@code jitterFraction} do valor. Determinístico para o mesmo {@code rng} (testável).
   */
  public static long delayMs(int tentativa, long baseMs, long capMs, double jitterFraction, RandomGenerator rng) {
    if (tentativa < 1) tentativa = 1;
    long exp = baseMs << (Math.min(tentativa - 1, 20)); // 2^(tentativa-1) sem estourar
    long base = Math.min(exp, capMs);
    long jitter = (long) (base * jitterFraction * rng.nextDouble());
    return base + jitter;
  }
}
