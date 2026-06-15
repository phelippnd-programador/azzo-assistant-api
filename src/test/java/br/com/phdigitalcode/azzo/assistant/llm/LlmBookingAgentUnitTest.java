package br.com.phdigitalcode.azzo.assistant.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.phdigitalcode.azzo.assistant.dialogue.ChatMessage;

@ExtendWith(MockitoExtension.class)
class LlmBookingAgentUnitTest {

  @Mock
  LlmRouter llmRouter;

  @InjectMocks
  LlmBookingAgent agent;

  @Test
  void deveRegistrarProviderRealQuandoHouverFallbackNoRouter() {
    when(llmRouter.select(any())).thenReturn(LlmRouter.Provider.GROQ);
    when(llmRouter.call(any(), anyString(), anyList(), any()))
        .thenReturn(new LlmRouter.LlmResponse("Resposta final", LlmRouter.Provider.OLLAMA));

    LlmBookingAgent.AgentResult result = agent.chat(
        "prompt",
        List.of(new ChatMessage("assistant", "Oi")),
        "quero agendar",
        null);

    assertEquals("OLLAMA", result.providerUsed());
    assertEquals("Resposta final", result.text());
    assertTrue(result.actions().isEmpty());
  }
}
