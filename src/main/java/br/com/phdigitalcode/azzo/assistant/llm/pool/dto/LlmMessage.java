package br.com.phdigitalcode.azzo.assistant.llm.pool.dto;

/** Mensagem normalizada da conversa, independente do formato de qualquer provedor. */
public record LlmMessage(String role, String content) {

  public static LlmMessage system(String content) {
    return new LlmMessage("system", content);
  }

  public static LlmMessage user(String content) {
    return new LlmMessage("user", content);
  }

  public static LlmMessage assistant(String content) {
    return new LlmMessage("assistant", content);
  }
}
