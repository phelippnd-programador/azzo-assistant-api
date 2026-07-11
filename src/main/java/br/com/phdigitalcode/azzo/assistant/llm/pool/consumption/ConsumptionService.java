package br.com.phdigitalcode.azzo.assistant.llm.pool.consumption;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Controle de capacidade e registro de consumo por credencial. Consulta os buckets
 * atômicos para decidir se uma credencial ainda tem limite disponível na janela e
 * registra o consumo após cada chamada.
 */
@ApplicationScoped
public class ConsumptionService {

  @Inject LlmConsumptionRepository repository;

  /**
   * Verifica se a credencial tem limite disponível para uma chamada estimada em
   * {@code tokensEstimados}, considerando os limites por minuto/dia/mês da credencial
   * e por minuto/dia do modelo. Não bloqueia quando não há limite configurado.
   */
  public boolean temCapacidade(LlmCredential credential, LlmModel model, long tokensEstimados, Instant agora) {
    if (credential == null) return false;

    // Limites de requisições da credencial
    if (excedeuRequisicoes(credential.id, credential.limiteRequisicoesMinuto, ConsumptionWindow.MINUTO, agora, 1)) return false;
    if (excedeuRequisicoes(credential.id, credential.limiteRequisicoesDia, ConsumptionWindow.DIA, agora, 1)) return false;

    // Limites de tokens da credencial
    if (excedeuTokens(credential.id, credential.limiteTokensMinuto, ConsumptionWindow.MINUTO, agora, tokensEstimados)) return false;
    if (excedeuTokens(credential.id, credential.limiteTokensDia, ConsumptionWindow.DIA, agora, tokensEstimados)) return false;
    if (excedeuTokens(credential.id, credential.limiteTokensMes, ConsumptionWindow.MES, agora, tokensEstimados)) return false;

    // Limites de requisições do modelo (aplicados sobre a mesma credencial)
    if (model != null) {
      if (excedeuRequisicoes(credential.id, model.limiteRequisicoesMinuto, ConsumptionWindow.MINUTO, agora, 1)) return false;
      if (excedeuRequisicoes(credential.id, model.limiteRequisicoesDia, ConsumptionWindow.DIA, agora, 1)) return false;
    }
    return true;
  }

  /** Fração de uso da janela mais restritiva (0..1+) — usada como sinal de saturação no scoring. */
  public double saturacao(LlmCredential credential, Instant agora) {
    double maior = 0.0;
    if (credential.limiteRequisicoesMinuto != null && credential.limiteRequisicoesMinuto > 0) {
      long usado = repository.requisicoesNaJanela(credential.id, ConsumptionWindow.MINUTO, agora);
      maior = Math.max(maior, (double) usado / credential.limiteRequisicoesMinuto);
    }
    if (credential.limiteRequisicoesDia != null && credential.limiteRequisicoesDia > 0) {
      long usado = repository.requisicoesNaJanela(credential.id, ConsumptionWindow.DIA, agora);
      maior = Math.max(maior, (double) usado / credential.limiteRequisicoesDia);
    }
    return maior;
  }

  /** Registra o consumo de uma chamada nas quatro janelas, atomicamente. */
  @Transactional
  public void registrar(UUID credentialId, long tokens, BigDecimal custo, Instant agora) {
    if (credentialId == null) return;
    for (ConsumptionWindow janela : ConsumptionWindow.values()) {
      repository.incrementar(credentialId, janela, agora, 1, tokens, custo);
    }
  }

  private boolean excedeuRequisicoes(UUID credId, Integer limite, ConsumptionWindow janela, Instant agora, int incremento) {
    if (limite == null || limite <= 0) return false;
    return repository.requisicoesNaJanela(credId, janela, agora) + incremento > limite;
  }

  private boolean excedeuTokens(UUID credId, Long limite, ConsumptionWindow janela, Instant agora, long incremento) {
    if (limite == null || limite <= 0) return false;
    return repository.tokensNaJanela(credId, janela, agora) + incremento > limite;
  }
}
