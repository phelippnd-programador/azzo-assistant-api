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
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.phdigitalcode.azzo.assistant.domain.repository.LlmUsageRepository;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

@ExtendWith(MockitoExtension.class)
class LlmRouterUnitTest {

  @Mock
  LocalLlmClient localLlmClient;

  @Mock
  GroqRestClient groqClient;

  @Mock
  LlmUsageRepository usageRepository;

  private LlmRouter router;

  @BeforeEach
  void setUp() {
    router = new LlmRouter();
    router.localLlmClient = localLlmClient;
    router.groqClient = groqClient;
    router.usageRepository = usageRepository;
    router.groqApiKey = "groq-key";
    router.groqModel = "llama-3.1-8b-instant";
    router.groqDailyLimit = 12000;
    router.groqEnabled = true;
    router.ollamaEnabled = true;
    router.ollamaModel = "azzo-atendimento";
    router.defaultMaxTokens = 220;
    router.defaultTemperature = 0.2;
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
    when(localLlmClient.chat(any())).thenReturn(respostaValida("fallback ok"));

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

  @Test
  void llmLocalRespostaValidaRetornaConteudo() {
    when(localLlmClient.chat(any())).thenReturn(respostaValida("Oi! Posso te ajudar."));

    LlmRouter.LlmResponse response = router.callStructured(
        LlmRouter.Provider.OLLAMA, List.of(new OllamaMessage("user", "oi")), 0.0, 60, false);

    assertEquals("Oi! Posso te ajudar.", response.text());
    assertEquals(LlmRouter.Provider.OLLAMA, response.provider());
  }

  @Test
  void llmLocalRespostaNulaCaiParaGroq() {
    router.groqEnabled = false; // sem fallback disponivel -> erro amigavel
    when(localLlmClient.chat(any())).thenReturn(null);

    LlmRouter.LlmResponse response = router.callStructured(
        LlmRouter.Provider.OLLAMA, List.of(new OllamaMessage("user", "oi")), 0.0, 60, false);

    assertTrue(response.isError());
  }

  @Test
  void llmLocalChoicesVazioCaiParaGroq() {
    router.groqEnabled = false;
    OpenAiChatResponse resposta = new OpenAiChatResponse();
    resposta.choices = List.of();
    when(localLlmClient.chat(any())).thenReturn(resposta);

    LlmRouter.LlmResponse response = router.callStructured(
        LlmRouter.Provider.OLLAMA, List.of(new OllamaMessage("user", "oi")), 0.0, 60, false);

    assertTrue(response.isError());
  }

  @Test
  void llmLocalMessageNulaCaiParaGroq() {
    router.groqEnabled = false;
    OpenAiChatResponse resposta = new OpenAiChatResponse();
    OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
    choice.message = null;
    resposta.choices = List.of(choice);
    when(localLlmClient.chat(any())).thenReturn(resposta);

    LlmRouter.LlmResponse response = router.callStructured(
        LlmRouter.Provider.OLLAMA, List.of(new OllamaMessage("user", "oi")), 0.0, 60, false);

    assertTrue(response.isError());
  }

  @Test
  void llmLocalConteudoVazioCaiParaGroq() {
    router.groqEnabled = false;
    when(localLlmClient.chat(any())).thenReturn(respostaValida(""));

    LlmRouter.LlmResponse response = router.callStructured(
        LlmRouter.Provider.OLLAMA, List.of(new OllamaMessage("user", "oi")), 0.0, 60, false);

    assertTrue(response.isError());
  }

  @Test
  void llmLocalHttp500CaiParaGroq() {
    router.groqEnabled = false;
    when(localLlmClient.chat(any())).thenThrow(
        new WebApplicationException("boom", Response.Status.INTERNAL_SERVER_ERROR));

    LlmRouter.LlmResponse response = router.callStructured(
        LlmRouter.Provider.OLLAMA, List.of(new OllamaMessage("user", "oi")), 0.0, 60, false);

    assertTrue(response.isError());
  }

  // ─── describeFailure: classificação de erro (item 6 da spec) ────────────────

  @Test
  void classificaHttp500ComoErroDeServidor() {
    Exception e = new WebApplicationException("boom", Response.Status.INTERNAL_SERVER_ERROR);
    assertTrue(LlmRouter.describeFailure(e).contains("500"));
  }

  @Test
  void classificaHttp400ComoRequisicaoInvalida() {
    Exception e = new WebApplicationException("boom", Response.Status.BAD_REQUEST);
    assertTrue(LlmRouter.describeFailure(e).contains("400"));
  }

  @Test
  void classificaTimeout() {
    Exception e = new RuntimeException("wrapper", new TimeoutException("deadline exceeded"));
    assertEquals("timeout", LlmRouter.describeFailure(e));
  }

  @Test
  void classificaConexaoRecusada() {
    Exception e = new RuntimeException("wrapper", new java.net.ConnectException("Connection refused"));
    assertTrue(LlmRouter.describeFailure(e).contains("conexão recusada"));
  }

  @Test
  void classificaJsonInvalido() {
    Exception e = new RuntimeException("wrapper",
        new com.fasterxml.jackson.core.JsonParseException(null, "unexpected token"));
    assertTrue(LlmRouter.describeFailure(e).contains("JSON inválido"));
  }

  @Test
  void preservaMensagensEstruturaisJaLegiveis() {
    Exception e = new IllegalStateException("lista 'choices' vazia na resposta do LLM local");
    assertEquals("lista 'choices' vazia na resposta do LLM local", LlmRouter.describeFailure(e));
  }

  private OpenAiChatResponse respostaValida(String content) {
    OpenAiChatResponse resposta = new OpenAiChatResponse();
    OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
    choice.message = new OllamaMessage("assistant", content);
    choice.finishReason = "stop";
    resposta.choices = List.of(choice);
    resposta.usage = new OpenAiChatResponse.Usage();
    resposta.usage.promptTokens = 100;
    resposta.usage.completionTokens = 20;
    resposta.usage.totalTokens = 120;
    return resposta;
  }
}
