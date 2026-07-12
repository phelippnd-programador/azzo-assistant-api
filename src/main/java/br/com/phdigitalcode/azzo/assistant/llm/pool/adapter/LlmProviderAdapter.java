package br.com.phdigitalcode.azzo.assistant.llm.pool.adapter;

import java.util.List;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.LlmProviderType;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmModelInfo;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmResponse;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.ProviderHealthResult;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.TokenUsage;

/**
 * Contrato de um adaptador de provedor de LLM. Cada família de provedor tem seu
 * adaptador, resolvido por {@link LlmAdapterRegistry} — sem condicionais espalhados
 * por {@code if (provider.equals("GROQ"))}. Provedores compatíveis com OpenAI
 * compartilham o mesmo adaptador base, configurável por dados (url_base, headers...).
 */
public interface LlmProviderAdapter {

  /** Tipo/família de provedor que este adaptador atende. */
  LlmProviderType getProviderType();

  /** Executa a chamada de chat e devolve a resposta normalizada. */
  LlmResponse enviar(LlmRequest request, AdapterCredential credential);

  /** Lista os modelos do provedor (vazio quando o provedor não oferece o endpoint). */
  List<LlmModelInfo> listarModelos(AdapterCredential credential);

  /** Teste seguro de conexão/autenticação, sem consumir franquia além do necessário. */
  ProviderHealthResult testarConexao(AdapterCredential credential);

  /** Extrai o uso de tokens da resposta bruta do provedor (quando o provedor informa). */
  TokenUsage extrairUsoTokens(Object providerResponse);

  boolean suportaStreaming();

  boolean suportaToolCalling();

  boolean suportaJsonMode();
}
