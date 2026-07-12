package br.com.phdigitalcode.azzo.assistant.llm.pool.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.phdigitalcode.azzo.assistant.llm.OllamaMessage;
import br.com.phdigitalcode.azzo.assistant.llm.OpenAiChatRequest;
import br.com.phdigitalcode.azzo.assistant.llm.OpenAiChatResponse;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.LlmProviderType;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmMessage;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmModelInfo;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmResponse;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.ProviderHealthResult;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.TokenUsage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Adaptador base para qualquer provedor com API compatível com OpenAI
 * ({@code /chat/completions}). Cobre, por configuração (url_base/headers/auth), a
 * maioria dos provedores da demanda — Groq, OpenRouter, Cerebras, Mistral, NVIDIA NIM,
 * Fireworks, Nebius, Novita, SambaNova, Hyperbolic, Scaleway, AI21, Upstage, GitHub
 * Models, Vercel AI Gateway, OpenCode Zen, Baseten, Inference.net, entre outros.
 *
 * <p>Sem duplicação por provedor: a diferenciação vive nos dados do {@link AdapterCredential}.
 */
@ApplicationScoped
public class OpenAiCompatibleAdapter implements LlmProviderAdapter {

  private static final Logger LOG = Logger.getLogger(OpenAiCompatibleAdapter.class);
  private static final String DEFAULT_CHAT_ENDPOINT = "/chat/completions";
  private static final String MODELS_ENDPOINT = "/models";

  // ObjectMapper próprio: serializa/desserializa como String, sem depender de um
  // MessageBodyReader/Writer JSON registrado no client (funciona dentro e fora do Quarkus).
  private static final ObjectMapper MAPPER = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  @Override
  public LlmProviderType getProviderType() {
    return LlmProviderType.OPENAI_COMPATIBLE;
  }

  @Override
  public LlmResponse enviar(LlmRequest request, AdapterCredential credential) {
    OpenAiChatRequest body = montarRequest(request);
    String endpoint = credential.endpointPath() == null || credential.endpointPath().isBlank()
        ? DEFAULT_CHAT_ENDPOINT : credential.endpointPath();

    Client client = novoClient(credential);
    try {
      WebTarget target = client.target(joinUrl(credential.urlBase(), endpoint));
      Invocation.Builder req = comHeaders(target.request(MediaType.APPLICATION_JSON_TYPE), credential);

      String reqJson = serializar(body);
      try (Response resp = req.post(Entity.entity(reqJson, MediaType.APPLICATION_JSON_TYPE))) {
        int status = resp.getStatus();
        String corpo = resp.readEntity(String.class);
        if (status >= 400) {
          throw erroHttp(status, resp.getHeaderString("Retry-After"), corpo);
        }
        OpenAiChatResponse parsed = parsear(corpo, OpenAiChatResponse.class, status);
        String texto = parsed != null ? parsed.text() : null;
        if (texto == null || texto.isBlank()) {
          throw new LlmProviderException(status, null, "resposta vazia do provedor");
        }
        TokenUsage uso = extrairUsoTokens(parsed);
        if (uso.total() == 0) {
          uso = estimar(request, texto); // provedor não retornou usage → estima e marca
        }
        String finish = parsed.choices != null && !parsed.choices.isEmpty()
            ? parsed.choices.get(0).finishReason : null;
        return LlmResponse.ok(texto.trim(), uso, finish);
      }
    } finally {
      client.close();
    }
  }

  @Override
  public List<LlmModelInfo> listarModelos(AdapterCredential credential) {
    Client client = novoClient(credential);
    try {
      WebTarget target = client.target(joinUrl(credential.urlBase(), MODELS_ENDPOINT));
      Invocation.Builder req = comHeaders(target.request(MediaType.APPLICATION_JSON_TYPE), credential);
      try (Response resp = req.get()) {
        int status = resp.getStatus();
        String corpo = resp.readEntity(String.class);
        if (status >= 400) {
          throw erroHttp(status, resp.getHeaderString("Retry-After"), corpo);
        }
        ModelsResponse parsed = parsear(corpo, ModelsResponse.class, status);
        List<LlmModelInfo> modelos = new ArrayList<>();
        if (parsed != null && parsed.data != null) {
          for (ModelsResponse.ModelEntry m : parsed.data) {
            if (m != null && m.id != null && !m.id.isBlank()) {
              modelos.add(new LlmModelInfo(m.id, m.id, null));
            }
          }
        }
        return modelos;
      }
    } finally {
      client.close();
    }
  }

  @Override
  public ProviderHealthResult testarConexao(AdapterCredential credential) {
    long start = System.currentTimeMillis();
    try {
      List<LlmModelInfo> modelos = listarModelos(credential);
      long latencia = System.currentTimeMillis() - start;
      return ProviderHealthResult.ok(credential.providerNome(), latencia, modelos.size());
    } catch (LlmProviderException e) {
      long latencia = System.currentTimeMillis() - start;
      String tipo = e.isAuthError() ? "AUTENTICACAO"
          : e.isRateLimited() ? "RATE_LIMIT"
          : e.isServerError() ? "SERVIDOR" : "ERRO";
      return ProviderHealthResult.falha(credential.providerNome(), latencia, tipo, mensagemSegura(e));
    } catch (Exception e) {
      long latencia = System.currentTimeMillis() - start;
      return ProviderHealthResult.falha(credential.providerNome(), latencia, "CONEXAO", "Falha ao conectar ao provedor");
    }
  }

  @Override
  public TokenUsage extrairUsoTokens(Object providerResponse) {
    if (providerResponse instanceof OpenAiChatResponse r && r.usage != null) {
      int in = r.usage.promptTokens;
      int out = r.usage.completionTokens;
      int total = r.usage.totalTokens > 0 ? r.usage.totalTokens : in + out;
      if (total > 0) return new TokenUsage(in, out, total, false);
    }
    return TokenUsage.vazio();
  }

  @Override
  public boolean suportaStreaming() {
    return true;
  }

  @Override
  public boolean suportaToolCalling() {
    return true;
  }

  @Override
  public boolean suportaJsonMode() {
    return true;
  }

  // ─── Internos ───────────────────────────────────────────────────────────────

  private OpenAiChatRequest montarRequest(LlmRequest request) {
    OpenAiChatRequest body = new OpenAiChatRequest();
    body.model = request.modelo;
    body.temperature = request.temperatura;
    body.maxTokens = request.maxTokens;
    body.stream = false;
    if (request.jsonMode) {
      body.responseFormat = new OpenAiChatRequest.ResponseFormat("json_object");
    }
    List<OllamaMessage> mensagens = new ArrayList<>();
    if (request.systemPrompt != null && !request.systemPrompt.isBlank()) {
      mensagens.add(new OllamaMessage("system", request.systemPrompt));
    }
    if (request.historico != null) {
      for (LlmMessage m : request.historico) {
        String role = "tool".equals(m.role()) ? "user" : m.role();
        mensagens.add(new OllamaMessage(role, m.content()));
      }
    }
    if (request.mensagemAtual != null && !request.mensagemAtual.isBlank()) {
      mensagens.add(new OllamaMessage("user", request.mensagemAtual));
    }
    body.messages = mensagens;
    return body;
  }

  /** Estimativa grosseira (~4 chars/token) marcada como estimada quando o provedor não retorna usage. */
  private TokenUsage estimar(LlmRequest request, String respostaTexto) {
    int entradaChars = 0;
    if (request.systemPrompt != null) entradaChars += request.systemPrompt.length();
    if (request.historico != null) {
      for (LlmMessage m : request.historico) {
        if (m.content() != null) entradaChars += m.content().length();
      }
    }
    if (request.mensagemAtual != null) entradaChars += request.mensagemAtual.length();
    int entrada = Math.max(1, entradaChars / 4);
    int saida = Math.max(1, (respostaTexto == null ? 0 : respostaTexto.length()) / 4);
    return TokenUsage.estimado(entrada, saida);
  }

  private Client novoClient(AdapterCredential credential) {
    return ClientBuilder.newBuilder()
        .connectTimeout(Math.max(1000, credential.timeoutConexaoMs()), TimeUnit.MILLISECONDS)
        .readTimeout(Math.max(2000, credential.timeoutRespostaMs()), TimeUnit.MILLISECONDS)
        .build();
  }

  private Invocation.Builder comHeaders(Invocation.Builder req, AdapterCredential credential) {
    String auth = credential.authHeaderValue();
    if (auth != null) {
      req = req.header("Authorization", auth);
    }
    Map<String, String> extras = credential.headersExtras();
    if (extras != null) {
      for (Map.Entry<String, String> e : extras.entrySet()) {
        if (e.getKey() != null && e.getValue() != null) {
          req = req.header(e.getKey(), e.getValue());
        }
      }
    }
    return req;
  }

  private LlmProviderException erroHttp(int status, String retryAfterHeader, String corpo) {
    Long retryAfterMs = parseRetryAfter(retryAfterHeader);
    // Nunca inclui headers de auth; apenas um trecho curto do corpo do erro.
    String trecho = corpo == null ? "" : corpo.substring(0, Math.min(180, corpo.length()));
    LOG.warnf("[OpenAiAdapter] HTTP %d ao chamar provedor: %s", status, trecho);
    return new LlmProviderException(status, retryAfterMs, "HTTP " + status);
  }

  private String serializar(OpenAiChatRequest body) {
    try {
      return MAPPER.writeValueAsString(body);
    } catch (Exception e) {
      throw new LlmProviderException(0, null, "falha ao serializar a requisição");
    }
  }

  private <T> T parsear(String corpo, Class<T> tipo, int status) {
    try {
      return MAPPER.readValue(corpo, tipo);
    } catch (Exception e) {
      throw new LlmProviderException(status, null, "resposta ilegível do provedor");
    }
  }

  private Long parseRetryAfter(String header) {
    if (header == null || header.isBlank()) return null;
    try {
      // Retry-After em segundos (formato mais comum nas APIs de LLM).
      return Long.parseLong(header.trim()) * 1000L;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String mensagemSegura(LlmProviderException e) {
    if (e.isAuthError()) return "Credencial inválida ou sem permissão";
    if (e.isRateLimited()) return "Limite de requisições atingido";
    if (e.isServerError()) return "Provedor indisponível no momento";
    return "Falha na comunicação com o provedor";
  }

  private static String joinUrl(String base, String path) {
    if (base == null) base = "";
    if (path == null) path = "";
    if (base.endsWith("/") && path.startsWith("/")) return base + path.substring(1);
    if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
    return base + path;
  }

  /** Resposta mínima do endpoint /models (formato OpenAI). */
  public static class ModelsResponse {
    public List<ModelEntry> data;

    public static class ModelEntry {
      public String id;
    }
  }
}
