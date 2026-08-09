package br.com.phdigitalcode.azzo.assistant.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.phdigitalcode.azzo.assistant.dialogue.ChatMessage;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmResponse;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.TokenUsage;
import br.com.phdigitalcode.azzo.assistant.llm.pool.execution.LlmPoolExecutor;

/** O chat do assistente atende exclusivamente pelo pool de provedores de LLM. */
@ExtendWith(MockitoExtension.class)
class LlmBookingAgentUnitTest {

  @Mock
  LlmPoolExecutor poolExecutor;

  @InjectMocks
  LlmBookingAgent agent;

  @Test
  void devolveTextoLimpoEAcoesQuandoPoolResponde() {
    when(poolExecutor.executar(any()))
        .thenReturn(LlmResponse.ok("Resposta final [CONSULTAR_HORARIOS:prof=P1|date=2026-07-20|svc=S1]",
            TokenUsage.exato(10, 5), "stop"));

    LlmBookingAgent.AgentResult result = agent.chat(
        "prompt",
        List.of(new ChatMessage("assistant", "Oi")),
        "quero agendar",
        null);

    assertEquals("POOL", result.providerUsed());
    assertEquals("Resposta final", result.text());
    assertTrue(result.hasAction("CONSULTAR_HORARIOS"));
    assertTrue(!result.llmUnavailable());
  }

  @Test
  void degradaGraciosamenteQuandoPoolNaoTemOpcaoElegivel() {
    when(poolExecutor.executar(any())).thenReturn(LlmResponse.falha());

    LlmBookingAgent.AgentResult result = agent.chat(
        "prompt",
        List.of(),
        "oi",
        null);

    assertTrue(result.llmUnavailable());
    assertEquals("POOL", result.providerUsed());
    assertTrue(result.actions().isEmpty());
  }

  @Test
  void degradaGraciosamenteQuandoPoolLancaExcecao() {
    when(poolExecutor.executar(any())).thenThrow(new RuntimeException("falha de infraestrutura"));

    LlmBookingAgent.AgentResult result = agent.chat(
        "prompt",
        List.of(),
        "oi",
        null);

    assertTrue(result.llmUnavailable());
    assertEquals("POOL", result.providerUsed());
  }
}
