package br.com.phdigitalcode.azzo.assistant.llm.pool.consumption;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Janela de contabilização de consumo. Calcula o início (UTC) da janela que contém
 * um instante — a chave usada nos buckets atômicos ({@code llm_consumption_bucket}).
 */
public enum ConsumptionWindow {
  MINUTO,
  HORA,
  DIA,
  MES;

  /** Início da janela (em UTC) que contém {@code agora}. */
  public Instant inicio(Instant agora) {
    return switch (this) {
      case MINUTO -> agora.truncatedTo(ChronoUnit.MINUTES);
      case HORA -> agora.truncatedTo(ChronoUnit.HOURS);
      case DIA -> agora.truncatedTo(ChronoUnit.DAYS);
      case MES -> agora.atZone(ZoneOffset.UTC)
          .withDayOfMonth(1)
          .truncatedTo(ChronoUnit.DAYS)
          .toInstant();
    };
  }
}
