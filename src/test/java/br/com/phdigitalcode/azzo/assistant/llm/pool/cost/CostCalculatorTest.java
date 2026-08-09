package br.com.phdigitalcode.azzo.assistant.llm.pool.cost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmModel;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.TokenUsage;
import org.junit.jupiter.api.Test;

class CostCalculatorTest {

  private final CostCalculator calc = new CostCalculator();

  private LlmModel model(String in, String out, String fixo, boolean gratuito) {
    LlmModel m = new LlmModel();
    m.custoInputPorMilhao = new BigDecimal(in);
    m.custoOutputPorMilhao = new BigDecimal(out);
    m.custoFixoPorChamada = new BigDecimal(fixo);
    m.gratuito = gratuito;
    return m;
  }

  @Test
  void modeloGratuitoTemCustoZeroEFranquia() {
    CostCalculator.CostResult r = calc.calcular(model("5", "15", "0", true), TokenUsage.exato(1000, 500));
    assertEquals(0, BigDecimal.ZERO.compareTo(r.custo()));
    assertTrue(r.franquiaGratuita());
  }

  @Test
  void calculaCustoPorMilhaoDeTokens() {
    // 1.000.000 input a $5/M = $5,00 ; 500.000 output a $15/M = $7,50 ; total $12,50
    CostCalculator.CostResult r = calc.calcular(model("5", "15", "0", false), TokenUsage.exato(1_000_000, 500_000));
    assertEquals(0, new BigDecimal("12.50000000").compareTo(r.custo()));
    assertFalse(r.franquiaGratuita());
  }

  @Test
  void somaCustoFixoPorChamada() {
    CostCalculator.CostResult r = calc.calcular(model("0", "0", "0.002", false), TokenUsage.exato(100, 100));
    assertEquals(0, new BigDecimal("0.00200000").compareTo(r.custo()));
  }
}
