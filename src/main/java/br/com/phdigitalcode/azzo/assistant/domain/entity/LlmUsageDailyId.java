package br.com.phdigitalcode.azzo.assistant.domain.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Chave composta para {@link LlmUsageDailyEntity}.
 * Representa a combinação única de data + provider.
 */
public class LlmUsageDailyId implements Serializable {

    public LocalDate usageDate;
    public String provider;

    public LlmUsageDailyId() {}

    public LlmUsageDailyId(LocalDate usageDate, String provider) {
        this.usageDate = usageDate;
        this.provider  = provider;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LlmUsageDailyId that)) return false;
        return Objects.equals(usageDate, that.usageDate)
            && Objects.equals(provider,  that.provider);
    }

    @Override
    public int hashCode() {
        return Objects.hash(usageDate, provider);
    }
}
