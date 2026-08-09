package br.com.phdigitalcode.azzo.assistant.llm.pool.admin;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmUsageHistory;
import br.com.phdigitalcode.azzo.assistant.llm.pool.resilience.CredentialCircuitBreaker;
import br.com.phdigitalcode.azzo.assistant.llm.pool.routing.RoutingMetricsService;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/** Consultas de consumo, saúde e histórico para o painel (seções 15, 19, 23). */
@ApplicationScoped
public class PoolInsightsService {

  @Inject EntityManager em;
  @Inject CredentialCircuitBreaker circuitBreaker;
  @Inject RoutingMetricsService metricsService;

  /** Resumo agregado do período (a partir de {@code desde}). */
  public Map<String, Object> resumo(Instant desde) {
    Object[] r = (Object[]) em.createNativeQuery(
            "SELECT COUNT(*), COALESCE(SUM(tokens_entrada),0), COALESCE(SUM(tokens_saida),0), "
                + "COALESCE(SUM(custo_estimado),0), COALESCE(AVG(latencia_ms),0), "
                + "COUNT(*) FILTER (WHERE status='SUCESSO'), COUNT(*) FILTER (WHERE fallback_utilizado), "
                + "COUNT(*) FILTER (WHERE franquia_gratuita) "
                + "FROM llm_usage_history WHERE data_requisicao >= :desde")
        .setParameter("desde", desde)
        .getSingleResult();

    long chamadas = num(r[0]);
    long sucesso = num(r[5]);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("chamadas", chamadas);
    m.put("tokensEntrada", num(r[1]));
    m.put("tokensSaida", num(r[2]));
    m.put("custoEstimado", r[3]);
    m.put("latenciaMediaMs", Math.round(((Number) r[4]).doubleValue()));
    m.put("taxaSucesso", chamadas > 0 ? Math.round((sucesso * 1000.0) / chamadas) / 10.0 : 0.0);
    m.put("fallbacks", num(r[6]));
    m.put("chamadasGratuitas", num(r[7]));
    return m;
  }

  /**
   * Métricas agregadas por PROVEDOR no período (chamadas, tokens, custo, latência
   * média, taxa de sucesso, fallbacks) — a partir do histórico de uso.
   */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> porProvedor(Instant desde) {
    List<Object[]> rows = em.createNativeQuery(
            "SELECT h.provider_id, p.nome, COUNT(*), COALESCE(SUM(h.tokens_entrada),0), "
                + "COALESCE(SUM(h.tokens_saida),0), COALESCE(SUM(h.custo_estimado),0), "
                + "COALESCE(AVG(h.latencia_ms),0), COUNT(*) FILTER (WHERE h.status='SUCESSO'), "
                + "COUNT(*) FILTER (WHERE h.fallback_utilizado) "
                + "FROM llm_usage_history h LEFT JOIN llm_provider p ON p.id = h.provider_id "
                + "WHERE h.data_requisicao >= :desde "
                + "GROUP BY h.provider_id, p.nome ORDER BY COUNT(*) DESC")
        .setParameter("desde", desde)
        .getResultList();

    List<Map<String, Object>> out = new java.util.ArrayList<>();
    for (Object[] r : rows) {
      long chamadas = num(r[2]);
      long sucesso = num(r[7]);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("providerId", r[0] != null ? r[0].toString() : null);
      m.put("nome", r[1] != null ? r[1] : "Desconhecido");
      m.put("chamadas", chamadas);
      m.put("tokensEntrada", num(r[3]));
      m.put("tokensSaida", num(r[4]));
      m.put("custoEstimado", r[5]);
      m.put("latenciaMediaMs", Math.round(((Number) r[6]).doubleValue()));
      m.put("taxaSucesso", chamadas > 0 ? Math.round((sucesso * 1000.0) / chamadas) / 10.0 : 0.0);
      m.put("fallbacks", num(r[8]));
      out.add(m);
    }
    return out;
  }

  /**
   * Métricas agregadas por CREDENCIAL (chave) no período, opcionalmente filtradas
   * por provedor.
   */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> porCredencial(UUID providerId, Instant desde) {
    StringBuilder sql = new StringBuilder(
        "SELECT h.credential_id, c.nome_identificacao, COUNT(*), COALESCE(SUM(h.tokens_entrada),0), "
            + "COALESCE(SUM(h.tokens_saida),0), COALESCE(SUM(h.custo_estimado),0), "
            + "COALESCE(AVG(h.latencia_ms),0), COUNT(*) FILTER (WHERE h.status='SUCESSO') "
            + "FROM llm_usage_history h LEFT JOIN llm_credential c ON c.id = h.credential_id "
            + "WHERE h.data_requisicao >= :desde ");
    if (providerId != null) sql.append("AND h.provider_id = :pid ");
    sql.append("GROUP BY h.credential_id, c.nome_identificacao ORDER BY COUNT(*) DESC");

    jakarta.persistence.Query q = em.createNativeQuery(sql.toString()).setParameter("desde", desde);
    if (providerId != null) q.setParameter("pid", providerId);
    List<Object[]> rows = q.getResultList();

    List<Map<String, Object>> out = new java.util.ArrayList<>();
    for (Object[] r : rows) {
      long chamadas = num(r[2]);
      long sucesso = num(r[7]);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("credentialId", r[0] != null ? r[0].toString() : null);
      m.put("nomeIdentificacao", r[1] != null ? r[1] : "Desconhecida");
      m.put("chamadas", chamadas);
      m.put("tokensEntrada", num(r[3]));
      m.put("tokensSaida", num(r[4]));
      m.put("custoEstimado", r[5]);
      m.put("latenciaMediaMs", Math.round(((Number) r[6]).doubleValue()));
      m.put("taxaSucesso", chamadas > 0 ? Math.round((sucesso * 1000.0) / chamadas) / 10.0 : 0.0);
      out.add(m);
    }
    return out;
  }

  /**
   * Métricas agregadas por MODELO no período, opcionalmente filtradas por provedor.
   */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> porModelo(UUID providerId, Instant desde) {
    StringBuilder sql = new StringBuilder(
        "SELECT h.model_id, m.nome_modelo, m.nome_exibicao, m.gratuito, COUNT(*), "
            + "COALESCE(SUM(h.tokens_entrada),0), COALESCE(SUM(h.tokens_saida),0), "
            + "COALESCE(SUM(h.custo_estimado),0), COALESCE(AVG(h.latencia_ms),0), "
            + "COUNT(*) FILTER (WHERE h.status='SUCESSO') "
            + "FROM llm_usage_history h LEFT JOIN llm_model m ON m.id = h.model_id "
            + "WHERE h.data_requisicao >= :desde ");
    if (providerId != null) sql.append("AND h.provider_id = :pid ");
    sql.append("GROUP BY h.model_id, m.nome_modelo, m.nome_exibicao, m.gratuito ORDER BY COUNT(*) DESC");

    jakarta.persistence.Query q = em.createNativeQuery(sql.toString()).setParameter("desde", desde);
    if (providerId != null) q.setParameter("pid", providerId);
    List<Object[]> rows = q.getResultList();

    List<Map<String, Object>> out = new java.util.ArrayList<>();
    for (Object[] r : rows) {
      long chamadas = num(r[4]);
      long sucesso = num(r[9]);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("modelId", r[0] != null ? r[0].toString() : null);
      m.put("nomeModelo", r[1] != null ? r[1] : "Desconhecido");
      m.put("nomeExibicao", r[2]);
      m.put("gratuito", r[3] != null && (Boolean) r[3]);
      m.put("chamadas", chamadas);
      m.put("tokensEntrada", num(r[5]));
      m.put("tokensSaida", num(r[6]));
      m.put("custoEstimado", r[7]);
      m.put("latenciaMediaMs", Math.round(((Number) r[8]).doubleValue()));
      m.put("taxaSucesso", chamadas > 0 ? Math.round((sucesso * 1000.0) / chamadas) / 10.0 : 0.0);
      out.add(m);
    }
    return out;
  }

  /** Histórico paginado com filtros opcionais. */
  public List<Map<String, Object>> historico(UUID providerId, String status, Instant desde, int page, int size) {
    StringBuilder jpql = new StringBuilder("1=1");
    Map<String, Object> params = new java.util.HashMap<>();
    if (providerId != null) { jpql.append(" and providerId = :pid"); params.put("pid", providerId); }
    if (status != null && !status.isBlank()) { jpql.append(" and status = :st"); params.put("st",
        br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.UsageStatus.valueOf(status)); }
    if (desde != null) { jpql.append(" and dataRequisicao >= :desde"); params.put("desde", desde); }

    List<LlmUsageHistory> lista = LlmUsageHistory.<LlmUsageHistory>find(jpql.toString(),
            Sort.by("dataRequisicao").descending(), params)
        .page(Page.of(Math.max(0, page), Math.min(Math.max(1, size), 200)))
        .list();

    return lista.stream().map(this::historicoView).toList();
  }

  private Map<String, Object> historicoView(LlmUsageHistory h) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", h.id != null ? h.id.toString() : null);
    m.put("providerId", h.providerId != null ? h.providerId.toString() : null);
    m.put("credentialId", h.credentialId != null ? h.credentialId.toString() : null);
    m.put("modelId", h.modelId != null ? h.modelId.toString() : null);
    m.put("tokensEntrada", h.tokensEntrada);
    m.put("tokensSaida", h.tokensSaida);
    m.put("tokensEstimados", h.tokensEstimados);
    m.put("custoEstimado", h.custoEstimado);
    m.put("latenciaMs", h.latenciaMs);
    m.put("status", h.status != null ? h.status.name() : null);
    m.put("codigoHttp", h.codigoHttp);
    m.put("tipoErro", h.tipoErro);
    m.put("tentativa", h.tentativa);
    m.put("fallbackUtilizado", h.fallbackUtilizado);
    m.put("dataRequisicao", h.dataRequisicao != null ? h.dataRequisicao.toString() : null);
    return m;
  }

  /** Saúde por credencial: métricas recentes + estado do circuit breaker + bloqueio. */
  public List<Map<String, Object>> saude() {
    Instant desde = Instant.now().minus(15, ChronoUnit.MINUTES);
    List<LlmCredential> creds = LlmCredential.list("removida = false");
    return creds.stream().map(c -> {
      RoutingMetricsService.Metrics met = metricsService.recentes(c.id, desde);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("credentialId", c.id.toString());
      m.put("nomeIdentificacao", c.nomeIdentificacao);
      m.put("ativo", c.ativo);
      m.put("latenciaMediaMs", Math.round(met.latenciaMediaMs()));
      m.put("taxaErro", Math.round(met.taxaErro() * 1000) / 10.0);
      m.put("circuitBreaker", circuitBreaker.state(c.id).name());
      m.put("bloqueadaAte", c.bloqueadaAte != null ? c.bloqueadaAte.toString() : null);
      m.put("estado", estado(c, met));
      return m;
    }).toList();
  }

  private String estado(LlmCredential c, RoutingMetricsService.Metrics met) {
    if (!c.ativo) return "DESATIVADO";
    if (c.bloqueadaAte != null && c.bloqueadaAte.isAfter(Instant.now())) return "LIMITADO";
    if (circuitBreaker.isOpen(c.id)) return "INDISPONIVEL";
    if (met.taxaErro() >= 0.3 || met.latenciaMediaMs() >= 4000) return "DEGRADADO";
    return "SAUDAVEL";
  }

  private long num(Object o) {
    return o == null ? 0L : ((Number) o).longValue();
  }
}
