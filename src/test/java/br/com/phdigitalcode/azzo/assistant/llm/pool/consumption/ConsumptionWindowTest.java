package br.com.phdigitalcode.azzo.assistant.llm.pool.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ConsumptionWindowTest {

  private static final Instant T = Instant.parse("2026-07-11T14:37:52Z");

  @Test
  void minutoTruncaParaOInicioDoMinuto() {
    assertEquals(Instant.parse("2026-07-11T14:37:00Z"), ConsumptionWindow.MINUTO.inicio(T));
  }

  @Test
  void horaTruncaParaOInicioDaHora() {
    assertEquals(Instant.parse("2026-07-11T14:00:00Z"), ConsumptionWindow.HORA.inicio(T));
  }

  @Test
  void diaTruncaParaMeiaNoiteUtc() {
    assertEquals(Instant.parse("2026-07-11T00:00:00Z"), ConsumptionWindow.DIA.inicio(T));
  }

  @Test
  void mesVaiParaOPrimeiroDiaDoMes() {
    assertEquals(Instant.parse("2026-07-01T00:00:00Z"), ConsumptionWindow.MES.inicio(T));
  }
}
