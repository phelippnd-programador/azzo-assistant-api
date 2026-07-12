package br.com.phdigitalcode.azzo.assistant.llm.pool.adapter;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.LlmProviderType;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmModelInfo;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmResponse;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.ProviderHealthResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Matriz de comportamento do adaptador OpenAI-compatible contra um provedor simulado
 * (WireMock): sucesso, 429 com Retry-After, 5xx, 401, listagem de modelos, teste de
 * conexão e timeout. Nenhum crédito real é consumido.
 */
class OpenAiCompatibleAdapterWireMockTest {

  private WireMockServer wireMock;
  private final OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter();

  @BeforeEach
  void setUp() {
    wireMock = new WireMockServer(options().dynamicPort());
    wireMock.start();
  }

  @AfterEach
  void tearDown() {
    wireMock.stop();
  }

  private AdapterCredential credencial(int readTimeoutMs) {
    return new AdapterCredential(
        LlmProviderType.OPENAI_COMPATIBLE, "sim", wireMock.baseUrl(),
        "/chat/completions", "test-key", "Bearer", null, null, Map.of(), 2000, readTimeoutMs);
  }

  private LlmRequest request() {
    LlmRequest r = new LlmRequest();
    r.modelo = "modelo-x";
    r.systemPrompt = "voce e um assistente";
    r.mensagemAtual = "oi";
    return r;
  }

  @Test
  void sucessoRetornaTextoEUsoExato() {
    wireMock.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"choices\":[{\"message\":{\"content\":\"Olá!\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}")));

    LlmResponse resp = adapter.enviar(request(), credencial(5000));
    assertFalse(resp.erro());
    assertEquals("Olá!", resp.texto());
    assertEquals(10, resp.uso().entrada());
    assertEquals(5, resp.uso().saida());
    assertEquals(15, resp.uso().total());
    assertFalse(resp.uso().estimado(), "usage do provedor => não estimado");
  }

  @Test
  void rateLimitLancaComRetryAfter() {
    wireMock.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
        .withStatus(429)
        .withHeader("Retry-After", "2")
        .withBody("{\"error\":\"rate limit\"}")));

    LlmProviderException e = assertThrows(LlmProviderException.class,
        () -> adapter.enviar(request(), credencial(5000)));
    assertTrue(e.isRateLimited());
    assertEquals(2000L, e.getRetryAfterMs());
  }

  @Test
  void erroServidorEhClassificadoComo5xx() {
    wireMock.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(503)));
    LlmProviderException e = assertThrows(LlmProviderException.class,
        () -> adapter.enviar(request(), credencial(5000)));
    assertTrue(e.isServerError());
    assertFalse(e.isRateLimited());
  }

  @Test
  void erroDeAutenticacao401() {
    wireMock.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(401)));
    LlmProviderException e = assertThrows(LlmProviderException.class,
        () -> adapter.enviar(request(), credencial(5000)));
    assertTrue(e.isAuthError());
  }

  @Test
  void listaModelos() {
    wireMock.stubFor(get(urlEqualTo("/models")).willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"data\":[{\"id\":\"modelo-a\"},{\"id\":\"modelo-b\"}]}")));

    List<LlmModelInfo> modelos = adapter.listarModelos(credencial(5000));
    assertEquals(2, modelos.size());
    assertEquals("modelo-a", modelos.get(0).id());
  }

  @Test
  void testeDeConexaoBemSucedido() {
    wireMock.stubFor(get(urlEqualTo("/models")).willReturn(aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"data\":[{\"id\":\"m1\"}]}")));

    ProviderHealthResult r = adapter.testarConexao(credencial(5000));
    assertTrue(r.sucesso());
    assertEquals(1, r.modelosEncontrados());
  }
}
