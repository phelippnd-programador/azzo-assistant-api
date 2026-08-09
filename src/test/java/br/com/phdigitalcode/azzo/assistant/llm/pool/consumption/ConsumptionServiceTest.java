package br.com.phdigitalcode.azzo.assistant.llm.pool.consumption;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import org.junit.jupiter.api.Test;

/**
 * Verifica a decisão de capacidade (chave próxima do limite / limite esgotado) sem
 * banco, usando um repositório-fake que devolve o consumo já registrado na janela.
 */
class ConsumptionServiceTest {

  private static final Instant AGORA = Instant.parse("2026-07-11T10:00:00Z");

  /** Repositório-fake: devolve valores canônicos de consumo por janela. */
  static class FakeRepo extends LlmConsumptionRepository {
    long requisicoes;
    long tokens;

    @Override public long requisicoesNaJanela(UUID c, ConsumptionWindow j, Instant a) { return requisicoes; }
    @Override public long tokensNaJanela(UUID c, ConsumptionWindow j, Instant a) { return tokens; }
  }

  private ConsumptionService service(FakeRepo repo) {
    ConsumptionService s = new ConsumptionService();
    s.repository = repo;
    return s;
  }

  private LlmCredential credencialComLimiteReqMinuto(int limite) {
    LlmCredential c = new LlmCredential();
    c.id = UUID.randomUUID();
    c.ativo = true;
    c.limiteRequisicoesMinuto = limite;
    return c;
  }

  @Test
  void temCapacidadeAbaixoDoLimite() {
    FakeRepo repo = new FakeRepo();
    repo.requisicoes = 3; // 3 + 1 = 4 <= 5
    assertTrue(service(repo).temCapacidade(credencialComLimiteReqMinuto(5), null, 100, AGORA));
  }

  @Test
  void semCapacidadeNoLimiteEsgotado() {
    FakeRepo repo = new FakeRepo();
    repo.requisicoes = 5; // 5 + 1 = 6 > 5
    assertFalse(service(repo).temCapacidade(credencialComLimiteReqMinuto(5), null, 100, AGORA));
  }

  @Test
  void semLimiteConfiguradoSemprePermite() {
    FakeRepo repo = new FakeRepo();
    repo.requisicoes = 999_999;
    LlmCredential c = new LlmCredential();
    c.id = UUID.randomUUID();
    c.ativo = true;
    // nenhum limite definido → não bloqueia
    assertTrue(service(repo).temCapacidade(c, null, 100, AGORA));
  }

  @Test
  void tokensAcimaDoLimiteBloqueiam() {
    FakeRepo repo = new FakeRepo();
    repo.tokens = 900;
    LlmCredential c = new LlmCredential();
    c.id = UUID.randomUUID();
    c.ativo = true;
    c.limiteTokensMinuto = 1000L; // 900 + 200 = 1100 > 1000
    assertFalse(service(repo).temCapacidade(c, null, 200, AGORA));
  }
}
