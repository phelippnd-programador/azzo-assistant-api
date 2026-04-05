package br.com.phdigitalcode.azzo.assistant.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.phdigitalcode.azzo.assistant.domain.repository.LlmUsageRepository;

@ExtendWith(MockitoExtension.class)
class LlmRouterUnitTest {

  @Mock
  OllamaRestClient ollamaClient;

  @Mock
  GroqRestClient groqClient;

  @Mock
  LlmUsageRepository usageRepository;

  private LlmRouter router;

  @BeforeEach
  void setUp() {
    router = new LlmRouter();
    router.ollamaClient = ollamaClient;
    router.groqClient = groqClient;
    router.usageRepository = usageRepository;
    router.groqApiKey = "groq-key";
    router.groqModel = "llama-3.1-8b-instant";
    router.groqDailyLimit = 12000;
    router.groqEnabled = true;
    router.ollamaEnabled = true;
    router.ollamaModel = "azzo-assistant-llama32";
    router.defaultMaxTokens = 220;
    router.groqRateLimitCooldownMs = 15000L;
  }

  @Test
  void deveAtivarCooldownQuandoGroqRetornarRateLimitDeTpm() {
    when(usageRepository.getCount(any(LocalDate.class), anyString())).thenReturn(0);
    when(groqClient.chat(anyString(), any()))
        .thenThrow(new RuntimeException(
            "Rate limit reached for model llama-3.1-8b-instant. "
                + "tokens per minute exceeded. Please try again in 710ms. "
                + "Code: rate_limit_exceeded"));

    OllamaChatResponse ollamaResponse = new OllamaChatResponse();
    ollamaResponse.message = new OllamaMessage("assistant", "fallback ok");
    when(ollamaClient.chat(any())).thenReturn(ollamaResponse);

    LlmRouter.LlmResponse response = router.call(
        LlmRouter.Provider.GROQ,
        "prompt",
        List.of(new OllamaMessage("user", "oi")),
        120);

    assertNotNull(response);
    assertEquals(LlmRouter.Provider.OLLAMA, response.provider());
    assertEquals(LlmRouter.Provider.OLLAMA, router.select(null));

    Map<String, Object> status = router.getCircuitBreakerStatus();
    @SuppressWarnings("unchecked")
    Map<String, Object> groqStatus = (Map<String, Object>) status.get("groq");
    assertNotNull(groqStatus.get("rate_limit_cooldown_seconds"));
    assertTrue(((Number) groqStatus.get("rate_limit_cooldown_seconds")).longValue() >= 0L);
  }
}
