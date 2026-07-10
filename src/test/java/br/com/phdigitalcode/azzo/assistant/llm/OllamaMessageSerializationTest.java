package br.com.phdigitalcode.azzo.assistant.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Regressão: mensagens de SAÍDA (request) não podem serializar
 * "reasoning_content" como null. Alguns chat templates Jinja (ex.: Qwen3,
 * llama-server rodando com --jinja) falham com
 * "type must be string, but is null" quando o campo vem presente e nulo
 * em vez de ausente.
 */
class OllamaMessageSerializationTest {

  @Test
  void mensagemDeSaidaNaoSerializaReasoningContentNulo() throws Exception {
    OllamaMessage message = new OllamaMessage("user", "Quero marcar um corte amanhã às 14h");

    String json = new ObjectMapper().writeValueAsString(message);

    assertFalse(json.contains("reasoning_content"),
        "reasoning_content nulo nao deveria aparecer na mensagem de saida: " + json);
    assertTrue(json.contains("\"role\":\"user\""));
    assertTrue(json.contains("\"content\":\"Quero marcar um corte amanhã às 14h\""));
  }

  @Test
  void requestCompletoNaoSerializaReasoningContentNuloEmNenhumaMensagem() throws Exception {
    OpenAiChatRequest request = new OpenAiChatRequest();
    request.model = "azzo-atendimento";
    request.stream = false;
    request.messages = java.util.List.of(
        new OllamaMessage("system", "Você interpreta mensagens de clientes de salão."),
        new OllamaMessage("user", "Quero marcar um corte amanhã às 14h"));

    String json = new ObjectMapper().writeValueAsString(request);

    assertFalse(json.contains("reasoning_content"), "raw: " + json);
    assertTrue(json.contains("\"stream\":false"));
  }
}
