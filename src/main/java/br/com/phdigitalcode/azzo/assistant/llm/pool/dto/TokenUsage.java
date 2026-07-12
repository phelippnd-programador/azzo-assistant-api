package br.com.phdigitalcode.azzo.assistant.llm.pool.dto;

/**
 * Uso de tokens de uma chamada. {@code estimado=true} quando o provedor não
 * retornou o consumo e os valores foram calculados por tokenizer/heurística.
 */
public record TokenUsage(int entrada, int saida, int total, boolean estimado) {

  public static TokenUsage exato(int entrada, int saida) {
    return new TokenUsage(entrada, saida, entrada + saida, false);
  }

  public static TokenUsage estimado(int entrada, int saida) {
    return new TokenUsage(entrada, saida, entrada + saida, true);
  }

  public static TokenUsage vazio() {
    return new TokenUsage(0, 0, 0, true);
  }
}
