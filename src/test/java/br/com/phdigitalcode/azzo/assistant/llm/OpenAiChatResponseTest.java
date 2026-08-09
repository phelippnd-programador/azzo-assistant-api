package br.com.phdigitalcode.azzo.assistant.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Regras puras de extração da resposta do LLM local (llama.cpp,
 * OpenAI-compatible): {@code choices[0].message.content}, com fallback
 * para {@code reasoning_content} e validação em cada nível da estrutura
 * (nunca deve lançar NullPointerException).
 */
class OpenAiChatResponseTest {

  @Test
  void extraiContentDaPrimeiraChoice() {
    OpenAiChatResponse response = new OpenAiChatResponse();
    OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
    choice.message = new OllamaMessage("assistant", "Oi! Posso te ajudar a agendar.");
    choice.finishReason = "stop";
    response.choices = List.of(choice);

    assertEquals("Oi! Posso te ajudar a agendar.", response.text());
    assertEquals("stop", response.finishReason());
    assertEquals(1, response.choiceCount());
  }

  @Test
  void usaReasoningContentQuandoContentVemVazio() {
    OpenAiChatResponse response = new OpenAiChatResponse();
    OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
    choice.message = new OllamaMessage("assistant", "");
    choice.message.reasoningContent = "resposta final via raciocinio";
    response.choices = List.of(choice);

    assertEquals("resposta final via raciocinio", response.text());
  }

  @Test
  void respostaNulaNaoLancaExcecao() {
    assertNull(OpenAiChatResponse.extract(null));
  }

  @Test
  void choicesNuloRetornaNuloSemExcecao() {
    OpenAiChatResponse response = new OpenAiChatResponse();
    response.choices = null;
    assertNull(response.text());
    assertEquals(0, response.choiceCount());
    assertNull(response.finishReason());
  }

  @Test
  void choicesVazioRetornaNuloSemExcecao() {
    OpenAiChatResponse response = new OpenAiChatResponse();
    response.choices = List.of();
    assertNull(response.text());
  }

  @Test
  void messageNulaRetornaNuloSemExcecao() {
    OpenAiChatResponse response = new OpenAiChatResponse();
    OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
    choice.message = null;
    response.choices = List.of(choice);
    assertNull(response.text());
  }

  @Test
  void contentEReasoningVaziosRetornaNulo() {
    OpenAiChatResponse response = new OpenAiChatResponse();
    OpenAiChatResponse.Choice choice = new OpenAiChatResponse.Choice();
    choice.message = new OllamaMessage("assistant", "   ");
    response.choices = List.of(choice);
    assertNull(response.text());
  }

  @Test
  void ignoraCamposDesconhecidosNoJson() throws Exception {
    String json = """
        {
          "id": "chatcmpl-123",
          "object": "chat.completion",
          "created": 1700000000,
          "model": "azzo-atendimento",
          "system_fingerprint": "fp_abc",
          "choices": [
            {
              "index": 0,
              "message": {"role": "assistant", "content": "Quero marcar amanha as 14h"},
              "finish_reason": "stop",
              "logprobs": null
            }
          ],
          "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}
        }
        """;

    OpenAiChatResponse response = new ObjectMapper().readValue(json, OpenAiChatResponse.class);

    assertEquals("Quero marcar amanha as 14h", response.text());
    assertEquals("stop", response.finishReason());
    assertEquals(100, response.usage.promptTokens);
    assertEquals(20, response.usage.completionTokens);
    assertEquals(120, response.usage.totalTokens);
  }
}
