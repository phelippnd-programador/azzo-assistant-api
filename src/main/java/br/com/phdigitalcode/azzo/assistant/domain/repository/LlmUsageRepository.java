package br.com.phdigitalcode.azzo.assistant.domain.repository;

import java.time.LocalDate;
import java.util.List;

import br.com.phdigitalcode.azzo.assistant.domain.entity.LlmUsageDailyEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

/**
 * Repositório para persistência do uso diário de LLM.
 *
 * <p>O incremento usa UPSERT nativo do PostgreSQL — atômico e seguro
 * para múltiplos threads simultâneos sem lock de aplicação.</p>
 */
@ApplicationScoped
public class LlmUsageRepository {

    @Inject
    EntityManager em;

    // ─── Leitura ──────────────────────────────────────────────────────────────

    /**
     * Retorna o total de requisições para um provider em uma data.
     * Retorna 0 se não houver registro (primeiro uso do dia).
     */
    public int getCount(LocalDate date, String provider) {
        try {
            Object result = em.createNativeQuery(
                "SELECT request_count FROM llm_usage_daily WHERE usage_date = ?1 AND provider = ?2")
                .setParameter(1, date)
                .setParameter(2, provider)
                .getSingleResult();
            return result != null ? ((Number) result).intValue() : 0;
        } catch (NoResultException e) {
            return 0;
        }
    }

    /**
     * Retorna os registros de uso dos últimos N dias, ordenados do mais recente.
     * Usado pelo endpoint de métricas.
     */
    public List<LlmUsageDailyEntity> getRecentUsage(int days) {
        return em.createQuery(
                "SELECT usage FROM LlmUsageDailyEntity usage " +
                "WHERE usage.usageDate >= :initialDate " +
                "ORDER BY usage.usageDate DESC, usage.provider ASC",
                LlmUsageDailyEntity.class)
            .setParameter("initialDate", LocalDate.now().minusDays(days))
            .getResultList();
    }

    // ─── Escrita ──────────────────────────────────────────────────────────────

    /**
     * Incrementa atomicamente o contador de um provider no dia informado.
     *
     * <p>Usa UPSERT PostgreSQL: se já existe a linha, incrementa; caso contrário,
     * cria com valor 1. Cada chamada abre e fecha sua própria transação curta.</p>
     */
    @Transactional
    public void increment(LocalDate date, String provider) {
        em.createNativeQuery(
            "INSERT INTO llm_usage_daily (usage_date, provider, request_count) " +
            "VALUES (?1, ?2, 1) " +
            "ON CONFLICT (usage_date, provider) " +
            "DO UPDATE SET request_count = llm_usage_daily.request_count + 1")
            .setParameter(1, date)
            .setParameter(2, provider)
            .executeUpdate();
    }
}
