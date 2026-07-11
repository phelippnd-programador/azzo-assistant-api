package br.com.phdigitalcode.azzo.assistant.llm.pool.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.RoutingStrategy;
import org.junit.jupiter.api.Test;

class LlmRoutingScorerTest {

  private final LlmRoutingScorer scorer = new LlmRoutingScorer();

  private RoutingCandidate cand(String nome, boolean gratuito, String custo,
      double latencia, double taxaErro, double saturacao, int prioridade, int peso) {
    RoutingCandidate c = new RoutingCandidate();
    c.credentialId = UUID.randomUUID();
    c.modeloNome = nome;
    c.gratuito = gratuito;
    c.custoEstimado = new BigDecimal(custo);
    c.latenciaMediaMs = latencia;
    c.taxaErro = taxaErro;
    c.saturacao = saturacao;
    c.prioridade = prioridade;
    c.peso = peso;
    return c;
  }

  @Test
  void menorCustoEscolheOMaisBarato() {
    RoutingCandidate barato = cand("barato", false, "0.001", 2000, 0, 0, 100, 100);
    RoutingCandidate caro = cand("caro", false, "0.010", 200, 0, 0, 100, 100);
    List<RoutingCandidate> ordem = scorer.ordenar(List.of(caro, barato), RoutingStrategy.MENOR_CUSTO);
    assertEquals("barato", ordem.get(0).modeloNome);
  }

  @Test
  void menorLatenciaEscolheOMaisRapido() {
    RoutingCandidate rapido = cand("rapido", false, "0.010", 150, 0, 0, 100, 100);
    RoutingCandidate lento = cand("lento", false, "0.001", 3000, 0, 0, 100, 100);
    List<RoutingCandidate> ordem = scorer.ordenar(List.of(lento, rapido), RoutingStrategy.MENOR_LATENCIA);
    assertEquals("rapido", ordem.get(0).modeloNome);
  }

  @Test
  void prioridadeFixaSegueAPrioridade() {
    RoutingCandidate alta = cand("alta", false, "0.010", 3000, 0.5, 0.9, 10, 100);
    RoutingCandidate baixa = cand("baixa", true, "0.000", 100, 0, 0, 200, 100);
    List<RoutingCandidate> ordem = scorer.ordenar(List.of(baixa, alta), RoutingStrategy.PRIORIDADE_FIXA);
    assertEquals("alta", ordem.get(0).modeloNome, "prioridade 10 vem antes de 200");
  }

  @Test
  void gratuitoPrimeiroPreferGratuitoMesmoQueMaisLento() {
    RoutingCandidate gratuitoLento = cand("gratis", true, "0.000", 3500, 0, 0.2, 100, 100);
    RoutingCandidate pagoRapido = cand("pago", false, "0.001", 120, 0, 0, 100, 100);
    List<RoutingCandidate> ordem = scorer.ordenar(List.of(pagoRapido, gratuitoLento), RoutingStrategy.GRATUITO_PRIMEIRO);
    assertEquals("gratis", ordem.get(0).modeloNome);
  }

  @Test
  void balanceadoPreferGratuitoRapidoComFolgaAoCaroLentoSaturado() {
    RoutingCandidate bom = cand("bom", true, "0.000", 300, 0.0, 0.1, 100, 100);
    RoutingCandidate ruim = cand("ruim", false, "0.010", 3500, 0.4, 0.95, 100, 100);
    List<RoutingCandidate> ordem = scorer.ordenar(List.of(ruim, bom), RoutingStrategy.BALANCEADO);
    assertEquals("bom", ordem.get(0).modeloNome);
  }

  @Test
  void balanceadoPenalizaAltaTaxaDeErro() {
    RoutingCandidate saudavel = cand("saudavel", false, "0.001", 800, 0.0, 0.3, 100, 100);
    RoutingCandidate comErros = cand("comErros", false, "0.001", 800, 0.6, 0.3, 100, 100);
    assertTrue(scorer.score(saudavel, RoutingStrategy.BALANCEADO)
        > scorer.score(comErros, RoutingStrategy.BALANCEADO));
  }

  @Test
  void roundRobinPonderadoPreferMaiorPesoComMesmaFolga() {
    RoutingCandidate pesado = cand("pesado", false, "0.001", 800, 0, 0.2, 100, 300);
    RoutingCandidate leve = cand("leve", false, "0.001", 800, 0, 0.2, 100, 50);
    List<RoutingCandidate> ordem = scorer.ordenar(List.of(leve, pesado), RoutingStrategy.ROUND_ROBIN_PONDERADO);
    assertEquals("pesado", ordem.get(0).modeloNome);
  }

  @Test
  void roundRobinPonderadoEvitaSaturado() {
    RoutingCandidate saturado = cand("saturado", false, "0.001", 800, 0, 0.98, 100, 300);
    RoutingCandidate folgado = cand("folgado", false, "0.001", 800, 0, 0.05, 100, 200);
    List<RoutingCandidate> ordem = scorer.ordenar(List.of(saturado, folgado), RoutingStrategy.ROUND_ROBIN_PONDERADO);
    assertEquals("folgado", ordem.get(0).modeloNome, "peso 200 com folga supera peso 300 saturado");
  }
}
