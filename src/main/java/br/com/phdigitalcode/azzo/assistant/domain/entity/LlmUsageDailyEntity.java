package br.com.phdigitalcode.azzo.assistant.domain.entity;

import java.time.LocalDate;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Contador diário de requisições LLM por provider.
 *
 * <p>Cada linha representa o total de chamadas feitas em um dia para um provider
 * específico (GROQ ou OLLAMA). O incremento é atômico via UPSERT PostgreSQL,
 * garantindo consistência mesmo sob concorrência.</p>
 *
 * <p>Persiste entre reinicializações — o {@link br.com.phdigitalcode.azzo.assistant.llm.LlmRouter}
 * carrega o valor do banco ao iniciar o dia e usa AtomicInteger como cache em memória.</p>
 */
@Entity
@Table(name = "llm_usage_daily")
@IdClass(LlmUsageDailyId.class)
public class LlmUsageDailyEntity extends PanacheEntityBase {

    @Id
    @Column(name = "usage_date", nullable = false)
    public LocalDate usageDate;

    @Id
    @Column(name = "provider", nullable = false, length = 20)
    public String provider;

    @Column(name = "request_count", nullable = false)
    public int requestCount;

    public LlmUsageDailyEntity() {}

    public LlmUsageDailyEntity(LocalDate usageDate, String provider, int requestCount) {
        this.usageDate    = usageDate;
        this.provider     = provider;
        this.requestCount = requestCount;
    }
}
