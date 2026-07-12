package br.com.phdigitalcode.azzo.assistant.llm.pool.adapter;

import java.util.Map;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.LlmProviderType;

/**
 * Dados de conexão + chave <b>já descriptografada</b> entregues ao adaptador apenas
 * no momento da chamada. Construído pela camada de serviço a partir do provedor e da
 * credencial; a chave em claro vive só o tempo da requisição e nunca é logada.
 */
public record AdapterCredential(
    LlmProviderType providerType,
    String providerNome,
    String urlBase,
    String endpointPath,
    String apiKeyPlain,
    String authScheme,
    String organizacao,
    String projeto,
    Map<String, String> headersExtras,
    int timeoutConexaoMs,
    int timeoutRespostaMs) {

  public String authHeaderValue() {
    if (apiKeyPlain == null || apiKeyPlain.isBlank()) return null;
    String scheme = authScheme == null || authScheme.isBlank() ? "Bearer" : authScheme;
    return scheme + " " + apiKeyPlain;
  }
}
