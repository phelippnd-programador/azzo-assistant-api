package br.com.phdigitalcode.azzo.assistant.llm.pool.routing;

import java.time.Instant;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Métricas passivas (latência média e taxa de erro recentes) derivadas do histórico
 * real de chamadas — sem jobs que consumam franquia gratuita (seção 19).
 */
@ApplicationScoped
public class RoutingMetricsService {

  @Inject EntityManager em;

  /** Latência média e taxa de erro da credencial desde {@code desde}. */
  public Metrics recentes(UUID credentialId, Instant desde) {
    if (credentialId == null) return Metrics.vazio();
    Object[] r = (Object[]) em.createNativeQuery(
            "SELECT COALESCE(AVG(latencia_ms), 0), COUNT(*), "
                + "COUNT(*) FILTER (WHERE status IN ('ERRO','TIMEOUT','RATE_LIMITED')) "
                + "FROM llm_usage_history WHERE credential_id = :cred AND data_requisicao >= :desde")
        .setParameter("cred", credentialId)
        .setParameter("desde", desde)
        .getSingleResult();

    double latencia = ((Number) r[0]).doubleValue();
    long total = ((Number) r[1]).longValue();
    long erros = ((Number) r[2]).longValue();
    double taxaErro = total > 0 ? (double) erros / total : 0.0;
    return new Metrics(latencia, taxaErro);
  }

  public record Metrics(double latenciaMediaMs, double taxaErro) {
    public static Metrics vazio() {
      return new Metrics(0.0, 0.0);
    }
  }
}
