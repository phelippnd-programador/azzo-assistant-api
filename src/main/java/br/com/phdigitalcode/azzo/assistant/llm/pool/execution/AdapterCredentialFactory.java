package br.com.phdigitalcode.azzo.assistant.llm.pool.execution;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.phdigitalcode.azzo.assistant.infrastructure.security.CredentialEncryptionService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.adapter.AdapterCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Constrói o {@link AdapterCredential} a partir do provedor e da credencial, decriptando
 * a chave apenas neste momento (menor tempo possível em memória, nunca logada). A config
 * do adaptador (endpoint, esquema de auth, headers extras) vem do JSON do provedor.
 */
@ApplicationScoped
public class AdapterCredentialFactory {

  private static final Logger LOG = Logger.getLogger(AdapterCredentialFactory.class);

  @Inject CredentialEncryptionService encryption;
  @Inject ObjectMapper objectMapper;

  public AdapterCredential build(LlmProvider provider, LlmCredential credential) {
    String apiKey = encryption.decrypt(credential.apiKeyCriptografada);
    return montar(provider, apiKey, credential.organizacao, credential.projeto);
  }

  /** Constrói a partir de uma chave em claro (ex.: teste de conexão antes de salvar). */
  public AdapterCredential buildComChave(LlmProvider provider, String apiKeyPlain,
      String organizacao, String projeto) {
    return montar(provider, apiKeyPlain, organizacao, projeto);
  }

  private AdapterCredential montar(LlmProvider provider, String apiKey, String organizacao, String projeto) {
    String endpoint = "/chat/completions";
    String authScheme = "Bearer";
    Map<String, String> headers = new HashMap<>();

    try {
      if (provider.configAdaptador != null && !provider.configAdaptador.isBlank()) {
        JsonNode cfg = objectMapper.readTree(provider.configAdaptador);
        if (cfg.hasNonNull("endpoint")) endpoint = cfg.get("endpoint").asText();
        if (cfg.hasNonNull("authScheme")) authScheme = cfg.get("authScheme").asText();
        JsonNode h = cfg.get("headers");
        if (h != null && h.isObject()) {
          for (Iterator<String> it = h.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            headers.put(k, h.get(k).asText());
          }
        }
      }
    } catch (Exception e) {
      // Config inválida não impede a chamada: usa os padrões OpenAI. Nunca loga a chave.
      LOG.warnf("[AdapterCredentialFactory] config_adaptador invalida para provider=%s: %s",
          provider.nome, e.getMessage());
    }

    return new AdapterCredential(
        provider.tipo,
        provider.nome,
        provider.urlBase,
        endpoint,
        apiKey,
        authScheme,
        organizacao,
        projeto,
        headers,
        provider.timeoutConexaoMs,
        provider.timeoutRespostaMs);
  }
}
