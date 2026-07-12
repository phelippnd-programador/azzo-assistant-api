package br.com.phdigitalcode.azzo.assistant.llm.pool.execution;

import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Bloqueio temporário durável de credencial (coluna {@code bloqueada_ate}). Usado ao
 * receber 429: respeita {@code Retry-After} e vale para todas as instâncias, evitando
 * novas tentativas imediatas na mesma chave (seções 10 e 20).
 */
@ApplicationScoped
public class CredentialBlockingService {

  @Inject EntityManager em;

  @Transactional
  public void bloquear(UUID credentialId, Instant ate) {
    if (credentialId == null || ate == null) return;
    em.createNativeQuery(
            "UPDATE llm_credential SET bloqueada_ate = :ate, data_atualizacao = now() WHERE id = :id")
        .setParameter("ate", ate)
        .setParameter("id", credentialId)
        .executeUpdate();
  }

  /** Remove o bloqueio administrativamente (seção 14: redefinir bloqueios). */
  @Transactional
  public void desbloquear(UUID credentialId) {
    if (credentialId == null) return;
    em.createNativeQuery(
            "UPDATE llm_credential SET bloqueada_ate = NULL, data_atualizacao = now() WHERE id = :id")
        .setParameter("id", credentialId)
        .executeUpdate();
  }
}
