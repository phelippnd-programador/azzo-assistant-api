package br.com.phdigitalcode.azzo.assistant.llm.pool.dto;

/**
 * Resultado do teste de conexão de uma credencial. Nunca inclui a chave.
 * Serializado para a API administrativa (seção 16).
 */
public record ProviderHealthResult(
    boolean sucesso,
    String provider,
    long latenciaMs,
    Integer modelosEncontrados,
    String tipoErro,
    String mensagem) {

  public static ProviderHealthResult ok(String provider, long latenciaMs, Integer modelos) {
    return new ProviderHealthResult(true, provider, latenciaMs, modelos, null, "Conexão realizada com sucesso");
  }

  public static ProviderHealthResult falha(String provider, long latenciaMs, String tipoErro, String mensagem) {
    return new ProviderHealthResult(false, provider, latenciaMs, null, tipoErro, mensagem);
  }
}
