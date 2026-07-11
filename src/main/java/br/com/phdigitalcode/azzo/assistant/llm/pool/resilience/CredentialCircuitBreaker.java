package br.com.phdigitalcode.azzo.assistant.llm.pool.resilience;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Circuit breaker por credencial (in-memory). Após {@link #THRESHOLD} falhas
 * consecutivas, abre o circuito por {@link #OPEN_MS}, fazendo o executor pular
 * aquela credencial imediatamente (sem esperar timeout). É uma otimização local
 * de resiliência; o bloqueio durável por 429 continua no banco ({@code bloqueada_ate}).
 */
@ApplicationScoped
public class CredentialCircuitBreaker {

  static final int THRESHOLD = 3;
  static final long OPEN_MS = 60_000L;

  public enum State { CLOSED, OPEN, HALF_OPEN }

  private static final class Entry {
    volatile int falhas = 0;
    volatile long abertoEm = 0L;
  }

  private final Map<UUID, Entry> estados = new ConcurrentHashMap<>();

  public State state(UUID credentialId) {
    Entry e = estados.get(credentialId);
    if (e == null || e.falhas < THRESHOLD) return State.CLOSED;
    long elapsed = System.currentTimeMillis() - e.abertoEm;
    return elapsed >= OPEN_MS ? State.HALF_OPEN : State.OPEN;
  }

  public boolean isOpen(UUID credentialId) {
    return state(credentialId) == State.OPEN;
  }

  public void onSuccess(UUID credentialId) {
    Entry e = estados.get(credentialId);
    if (e != null) {
      e.falhas = 0;
      e.abertoEm = 0L;
    }
  }

  public void onFailure(UUID credentialId) {
    Entry e = estados.computeIfAbsent(credentialId, k -> new Entry());
    e.falhas++;
    if (e.falhas >= THRESHOLD) {
      e.abertoEm = System.currentTimeMillis();
    }
  }

  /** Falhas consecutivas atuais (para métricas/saúde). */
  public int falhas(UUID credentialId) {
    Entry e = estados.get(credentialId);
    return e == null ? 0 : e.falhas;
  }
}
