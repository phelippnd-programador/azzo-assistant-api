package br.com.phdigitalcode.azzo.assistant.llm.pool.dto;

/** Modelo retornado por um provedor ao listar/sincronizar modelos. */
public record LlmModelInfo(String id, String nomeExibicao, Integer contextWindow) {

  public static LlmModelInfo of(String id) {
    return new LlmModelInfo(id, id, null);
  }
}
