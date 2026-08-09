package br.com.phdigitalcode.azzo.assistant.llm.pool.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CredentialCircuitBreakerTest {

  @Test
  void abreAposTresFalhasConsecutivas() {
    CredentialCircuitBreaker cb = new CredentialCircuitBreaker();
    UUID id = UUID.randomUUID();

    assertFalse(cb.isOpen(id));
    assertEquals(CredentialCircuitBreaker.State.CLOSED, cb.state(id));

    cb.onFailure(id);
    cb.onFailure(id);
    assertFalse(cb.isOpen(id), "duas falhas ainda mantem fechado");

    cb.onFailure(id); // 3a falha
    assertTrue(cb.isOpen(id), "abre no limite de 3 falhas");
    assertEquals(CredentialCircuitBreaker.State.OPEN, cb.state(id));
  }

  @Test
  void sucessoFechaOCircuito() {
    CredentialCircuitBreaker cb = new CredentialCircuitBreaker();
    UUID id = UUID.randomUUID();
    cb.onFailure(id);
    cb.onFailure(id);
    cb.onFailure(id);
    assertTrue(cb.isOpen(id));

    cb.onSuccess(id);
    assertFalse(cb.isOpen(id));
    assertEquals(0, cb.falhas(id));
    assertEquals(CredentialCircuitBreaker.State.CLOSED, cb.state(id));
  }

  @Test
  void credenciaisSaoIndependentes() {
    CredentialCircuitBreaker cb = new CredentialCircuitBreaker();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    cb.onFailure(a);
    cb.onFailure(a);
    cb.onFailure(a);
    assertTrue(cb.isOpen(a));
    assertFalse(cb.isOpen(b), "falha em uma credencial nao afeta outra");
  }
}
