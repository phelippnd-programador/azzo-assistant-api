package br.com.phdigitalcode.azzo.assistant.llm.pool.consumption;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.inject.Inject;

/**
 * Acesso aos buckets de consumo por janela. O incremento usa
 * {@code INSERT ... ON CONFLICT DO UPDATE}, garantindo contagem correta mesmo com
 * várias instâncias da aplicação atualizando a mesma credencial simultaneamente —
 * sem contadores em memória (seção 20).
 */
@ApplicationScoped
public class LlmConsumptionRepository {

  @Inject EntityManager em;

  private static final String UPSERT = """
      INSERT INTO llm_consumption_bucket
        (id, credential_id, janela, janela_inicio, requisicoes, tokens, custo, data_criacao, data_atualizacao)
      VALUES (:id, :cred, :janela, :inicio, :req, :tok, :custo, now(), now())
      ON CONFLICT (credential_id, janela, janela_inicio) DO UPDATE SET
        requisicoes = llm_consumption_bucket.requisicoes + :req,
        tokens      = llm_consumption_bucket.tokens + :tok,
        custo       = llm_consumption_bucket.custo + :custo,
        data_atualizacao = now()
      """;

  /** Incrementa atomicamente o bucket da credencial na janela informada. */
  public void incrementar(UUID credentialId, ConsumptionWindow janela, Instant agora,
      int requisicoes, long tokens, BigDecimal custo) {
    Query q = em.createNativeQuery(UPSERT);
    q.setParameter("id", UUID.randomUUID());
    q.setParameter("cred", credentialId);
    q.setParameter("janela", janela.name());
    q.setParameter("inicio", janela.inicio(agora));
    q.setParameter("req", requisicoes);
    q.setParameter("tok", tokens);
    q.setParameter("custo", custo == null ? BigDecimal.ZERO : custo);
    q.executeUpdate();
  }

  /** Requisições já consumidas pela credencial na janela atual (0 se não houver bucket). */
  public long requisicoesNaJanela(UUID credentialId, ConsumptionWindow janela, Instant agora) {
    Object r = em.createNativeQuery(
            "SELECT requisicoes FROM llm_consumption_bucket "
                + "WHERE credential_id = :cred AND janela = :janela AND janela_inicio = :inicio")
        .setParameter("cred", credentialId)
        .setParameter("janela", janela.name())
        .setParameter("inicio", janela.inicio(agora))
        .getResultStream().findFirst().orElse(null);
    return r == null ? 0L : ((Number) r).longValue();
  }

  /** Tokens já consumidos pela credencial na janela atual (0 se não houver bucket). */
  public long tokensNaJanela(UUID credentialId, ConsumptionWindow janela, Instant agora) {
    Object r = em.createNativeQuery(
            "SELECT tokens FROM llm_consumption_bucket "
                + "WHERE credential_id = :cred AND janela = :janela AND janela_inicio = :inicio")
        .setParameter("cred", credentialId)
        .setParameter("janela", janela.name())
        .setParameter("inicio", janela.inicio(agora))
        .getResultStream().findFirst().orElse(null);
    return r == null ? 0L : ((Number) r).longValue();
  }
}
