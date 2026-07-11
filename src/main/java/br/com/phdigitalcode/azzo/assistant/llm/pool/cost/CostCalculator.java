package br.com.phdigitalcode.azzo.assistant.llm.pool.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmModel;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Calcula o custo estimado de uma chamada a partir dos custos configuráveis do modelo
 * (por milhão de tokens + custo fixo por chamada). Modelo gratuito → custo zero com a
 * franquia identificada (seções 18 e 9).
 */
@ApplicationScoped
public class CostCalculator {

  private static final BigDecimal UM_MILHAO = new BigDecimal("1000000");

  public CostResult calcular(LlmModel model, TokenUsage uso) {
    if (model == null || uso == null) {
      return new CostResult(BigDecimal.ZERO, false);
    }
    if (model.gratuito) {
      // Dentro da franquia gratuita: registra custo zero e identifica o uso da franquia.
      return new CostResult(BigDecimal.ZERO, true);
    }

    BigDecimal custoInput = nz(model.custoInputPorMilhao)
        .multiply(BigDecimal.valueOf(uso.entrada()))
        .divide(UM_MILHAO, 10, RoundingMode.HALF_UP);
    BigDecimal custoOutput = nz(model.custoOutputPorMilhao)
        .multiply(BigDecimal.valueOf(uso.saida()))
        .divide(UM_MILHAO, 10, RoundingMode.HALF_UP);
    BigDecimal total = custoInput.add(custoOutput).add(nz(model.custoFixoPorChamada))
        .setScale(8, RoundingMode.HALF_UP);
    return new CostResult(total, false);
  }

  private BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  /** Resultado do cálculo: custo estimado e se a chamada usou franquia gratuita. */
  public record CostResult(BigDecimal custo, boolean franquiaGratuita) {}
}
