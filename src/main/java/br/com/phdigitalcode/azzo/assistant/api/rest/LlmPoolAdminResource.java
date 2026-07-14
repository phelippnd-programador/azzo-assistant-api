package br.com.phdigitalcode.azzo.assistant.api.rest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.CredentialAdminService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.CredentialRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.CredentialView;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.ModelRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.ModelView;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.ProviderRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.ProviderView;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.TestConnectionRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.ModelAdminService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.PoolInsightsService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.PoolTestConnectionService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.ProviderAdminService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.RoutingStrategy;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.ProviderHealthResult;
import br.com.phdigitalcode.azzo.assistant.llm.pool.routing.LlmRoutingService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.routing.RoutingStrategyHolder;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * API administrativa do pool de provedores de LLM. Protegida pelo InternalApiKeyFilter
 * (prefixo /api/v1/assistant). A permissão de usuário GERENCIAR_PROVEDORES_LLM é
 * aplicada na camada api-gerenciamento, que autentica o ADM e encaminha com a chave
 * interna. Nenhuma resposta expõe a chave em claro — apenas a máscara.
 */
@Path("/api/v1/assistant/admin/llm-pool")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class LlmPoolAdminResource {

  @Inject ProviderAdminService providerService;
  @Inject CredentialAdminService credentialService;
  @Inject ModelAdminService modelService;
  @Inject PoolTestConnectionService testConnectionService;
  @Inject PoolInsightsService insightsService;
  @Inject LlmRoutingService routingService;
  @Inject RoutingStrategyHolder strategyHolder;

  // ─── Provedores ─────────────────────────────────────────────────────────────

  @GET @Path("/providers")
  public List<ProviderView> listarProvedores() {
    return providerService.listar();
  }

  @GET @Path("/providers/{id}")
  public ProviderView obterProvedor(@PathParam("id") UUID id) {
    return providerService.obter(id);
  }

  @POST @Path("/providers")
  public ProviderView criarProvedor(@Valid ProviderRequest req, @HeaderParam("X-User-Name") String usuario) {
    return providerService.criar(req, usuario);
  }

  @PUT @Path("/providers/{id}")
  public ProviderView atualizarProvedor(@PathParam("id") UUID id, @Valid ProviderRequest req,
      @HeaderParam("X-User-Name") String usuario) {
    return providerService.atualizar(id, req, usuario);
  }

  @POST @Path("/providers/{id}/ativar")
  public void ativarProvedor(@PathParam("id") UUID id, @QueryParam("ativo") @DefaultValue("true") boolean ativo,
      @HeaderParam("X-User-Name") String usuario) {
    providerService.definirAtivo(id, ativo, usuario);
  }

  // ─── Credenciais ────────────────────────────────────────────────────────────

  @GET @Path("/credentials")
  public List<CredentialView> listarCredenciais(@QueryParam("providerId") UUID providerId) {
    return credentialService.listar(providerId);
  }

  @POST @Path("/credentials")
  public CredentialView criarCredencial(@Valid CredentialRequest req, @HeaderParam("X-User-Name") String usuario) {
    return credentialService.criar(req, usuario);
  }

  @PUT @Path("/credentials/{id}")
  public CredentialView atualizarCredencial(@PathParam("id") UUID id, @Valid CredentialRequest req,
      @HeaderParam("X-User-Name") String usuario) {
    return credentialService.atualizar(id, req, usuario);
  }

  @POST @Path("/credentials/{id}/ativar")
  public void ativarCredencial(@PathParam("id") UUID id, @QueryParam("ativo") @DefaultValue("true") boolean ativo,
      @HeaderParam("X-User-Name") String usuario) {
    credentialService.definirAtivo(id, ativo, usuario);
  }

  @POST @Path("/credentials/{id}/desbloquear")
  public void desbloquearCredencial(@PathParam("id") UUID id, @HeaderParam("X-User-Name") String usuario) {
    credentialService.desbloquear(id, usuario);
  }

  @DELETE @Path("/credentials/{id}")
  public void removerCredencial(@PathParam("id") UUID id, @HeaderParam("X-User-Name") String usuario) {
    credentialService.remover(id, usuario);
  }

  // ─── Modelos ────────────────────────────────────────────────────────────────

  @GET @Path("/providers/{providerId}/models")
  public List<ModelView> listarModelos(@PathParam("providerId") UUID providerId) {
    return modelService.listar(providerId);
  }

  @POST @Path("/providers/{providerId}/models")
  public ModelView criarModelo(@PathParam("providerId") UUID providerId, @Valid ModelRequest req,
      @HeaderParam("X-User-Name") String usuario) {
    return modelService.criar(providerId, req, usuario);
  }

  @PUT @Path("/models/{id}")
  public ModelView atualizarModelo(@PathParam("id") UUID id, @Valid ModelRequest req,
      @HeaderParam("X-User-Name") String usuario) {
    return modelService.atualizar(id, req, usuario);
  }

  @POST @Path("/models/{id}/ativar")
  public void ativarModelo(@PathParam("id") UUID id, @QueryParam("ativo") @DefaultValue("true") boolean ativo,
      @HeaderParam("X-User-Name") String usuario) {
    modelService.definirAtivo(id, ativo, usuario);
  }

  @POST @Path("/providers/{providerId}/models/sincronizar")
  public List<ModelView> sincronizarModelos(@PathParam("providerId") UUID providerId,
      @HeaderParam("X-User-Name") String usuario) {
    return modelService.sincronizar(providerId, usuario);
  }

  // ─── Teste de conexão ───────────────────────────────────────────────────────

  @POST @Path("/test-connection")
  public ProviderHealthResult testarConexao(TestConnectionRequest req, @HeaderParam("X-User-Name") String usuario) {
    return testConnectionService.testar(req, usuario);
  }

  // ─── Consumo / saúde / histórico ────────────────────────────────────────────

  @GET @Path("/resumo")
  public Map<String, Object> resumo(@QueryParam("horas") @DefaultValue("24") int horas) {
    return insightsService.resumo(desde(horas));
  }

  @GET @Path("/saude")
  public List<Map<String, Object>> saude() {
    return insightsService.saude();
  }

  /** Métricas agregadas por provedor (chamadas, tokens, custo, latência, sucesso). */
  @GET @Path("/metrics/providers")
  public List<Map<String, Object>> metricasPorProvedor(@QueryParam("horas") @DefaultValue("24") int horas) {
    return insightsService.porProvedor(desde(horas));
  }

  /** Métricas agregadas por credencial (chave), opcionalmente filtradas por provedor. */
  @GET @Path("/metrics/credentials")
  public List<Map<String, Object>> metricasPorCredencial(
      @QueryParam("providerId") UUID providerId, @QueryParam("horas") @DefaultValue("24") int horas) {
    return insightsService.porCredencial(providerId, desde(horas));
  }

  /** Métricas agregadas por modelo, opcionalmente filtradas por provedor. */
  @GET @Path("/metrics/models")
  public List<Map<String, Object>> metricasPorModelo(
      @QueryParam("providerId") UUID providerId, @QueryParam("horas") @DefaultValue("24") int horas) {
    return insightsService.porModelo(providerId, desde(horas));
  }

  @GET @Path("/historico")
  public List<Map<String, Object>> historico(
      @QueryParam("providerId") UUID providerId,
      @QueryParam("status") String status,
      @QueryParam("horas") @DefaultValue("24") int horas,
      @QueryParam("page") @DefaultValue("0") int page,
      @QueryParam("size") @DefaultValue("50") int size) {
    return insightsService.historico(providerId, status, desde(horas), page, size);
  }

  // ─── Estratégia de roteamento ───────────────────────────────────────────────

  @GET @Path("/estrategia")
  public Map<String, Object> obterEstrategia() {
    return Map.of(
        "estrategia", routingService.estrategiaAtual().name(),
        "disponiveis", java.util.Arrays.stream(RoutingStrategy.values()).map(Enum::name).toList());
  }

  @POST @Path("/estrategia")
  public Map<String, Object> definirEstrategia(Map<String, String> body) {
    String valor = body != null ? body.get("estrategia") : null;
    if (valor == null) {
      throw new jakarta.ws.rs.BadRequestException("Informe 'estrategia'");
    }
    strategyHolder.definir(RoutingStrategy.valueOf(valor));
    return Map.of("estrategia", routingService.estrategiaAtual().name());
  }

  private Instant desde(int horas) {
    return Instant.now().minus(Math.max(1, horas), ChronoUnit.HOURS);
  }
}
