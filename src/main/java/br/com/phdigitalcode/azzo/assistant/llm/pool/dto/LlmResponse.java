package br.com.phdigitalcode.azzo.assistant.llm.pool.dto;

/** Resposta normalizada de um provedor. */
public record LlmResponse(String texto, TokenUsage uso, String finishReason, boolean erro) {

  public static LlmResponse ok(String texto, TokenUsage uso, String finishReason) {
    return new LlmResponse(texto, uso, finishReason, false);
  }

  public static LlmResponse falha() {
    return new LlmResponse(null, TokenUsage.vazio(), null, true);
  }

  public boolean vazia() {
    return texto == null || texto.isBlank();
  }
}
