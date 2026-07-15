package br.com.phdigitalcode.azzo.assistant.api.rest;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import br.com.phdigitalcode.azzo.assistant.application.service.AssistantConversationService;
import br.com.phdigitalcode.azzo.assistant.application.service.ConversationStateManager;
import br.com.phdigitalcode.azzo.assistant.llm.AgentSystemPromptBuilder;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageRequest;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageResponse;
import br.com.phdigitalcode.azzo.assistant.model.AssistantReactivationSeedRequest;
import br.com.phdigitalcode.azzo.assistant.training.OpenNLPModelTrainer;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Endpoints do assistente. TODOS os metodos abaixo (/message e /admin/*) sao
 * protegidos pelo InternalApiKeyFilter, que exige o header X-Internal-Api-Key.
 *
 * SEC-010: os headers X-Tenant-Id / X-User-Identifier / X-User-Name so sao
 * confiaveis porque o chamador ja foi autenticado como servico interno pela
 * chave compartilhada. Nao expor estes endpoints publicamente sem o filtro.
 */
@Path("/api/v1/assistant")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AssistantResource {

  private static final Logger LOG = Logger.getLogger(AssistantResource.class);

  @Inject AssistantConversationService conversationService;
  @Inject OpenNLPModelTrainer modelTrainer;
  @Inject AgentSystemPromptBuilder agentSystemPromptBuilder;
  @Inject ConversationStateManager stateManager;

  @ConfigProperty(name = "app.assistant.debug", defaultValue = "false")
  boolean debugEnabled;

  @POST
  @Path("/message")
  public AssistantMessageResponse message(
      @Valid AssistantMessageRequest request,
      @HeaderParam("X-Tenant-Id") String tenantId,
      @HeaderParam("X-User-Identifier") String userIdentifier,
      @HeaderParam("X-User-Name") String userName) {
    int msgLen = request.message != null ? request.message.length() : 0;
    if (debugEnabled) {
      LOG.debugf("assistant.flow.message.received tenantId=%s userIdentifier=%s messageLength=%d message=%s",
          tenantId, userIdentifier, msgLen, request.message);
    }

    Instant start = debugEnabled ? Instant.now() : null;

    if (debugEnabled) {
      LOG.debugf("assistant.flow.service.calling tenantId=%s userIdentifier=%s messageLength=%d",
          tenantId, userIdentifier, msgLen);
    }

    AssistantMessageResponse response = conversationService.process(request.message, userIdentifier, userName);

    if (debugEnabled) {
      long elapsedMs = java.time.Duration.between(start, Instant.now()).toMillis();
      String replySnippet = response != null && response.reply != null
          ? (response.reply.length() > 120 ? response.reply.substring(0, 120) + "..." : response.reply)
          : "null";
      LOG.debugf("assistant.flow.service.replied tenantId=%s userIdentifier=%s stage=%s replyLength=%d elapsedMs=%d reply=%s",
          tenantId, userIdentifier,
          response != null ? response.stage : "null",
          response != null && response.reply != null ? response.reply.length() : 0,
          elapsedMs,
          replySnippet);
      LOG.debugf("assistant.flow.response.sent tenantId=%s userIdentifier=%s stage=%s elapsedMs=%d",
          tenantId, userIdentifier,
          response != null ? response.stage : "null",
          elapsedMs);
    }

    return response;
  }

  @POST
  @Path("/admin/seed-reminder")
  public Map<String, Object> seedReminder(
      @QueryParam("tenantId") String tenantId,
      @QueryParam("userIdentifier") String userIdentifier,
      @QueryParam("appointmentId") String appointmentId,
      @QueryParam("customerName") String customerName) {
    if (tenantId == null || userIdentifier == null || appointmentId == null) {
      return Map.of("status", "ERROR", "message", "tenantId, userIdentifier e appointmentId sao obrigatorios");
    }
    try {
      stateManager.seedReminderContext(
          UUID.fromString(tenantId),
          userIdentifier,
          UUID.fromString(appointmentId),
          customerName != null ? customerName : "");
      return Map.of("status", "OK", "appointmentId", appointmentId, "userIdentifier", userIdentifier);
    } catch (IllegalArgumentException e) {
      return Map.of("status", "ERROR", "message", "UUID invalido: " + e.getMessage());
    }
  }

  @POST
  @Path("/admin/seed-reactivation")
  public Map<String, Object> seedReactivation(
      @QueryParam("tenantId") String tenantId,
      @QueryParam("userIdentifier") String userIdentifier,
      @Valid AssistantReactivationSeedRequest request) {
    if (tenantId == null || tenantId.isBlank() || userIdentifier == null || userIdentifier.isBlank() || request == null) {
      return Map.of("status", "ERROR", "message", "tenantId, userIdentifier e payload sao obrigatorios");
    }
    try {
      stateManager.seedReactivationContext(
          UUID.fromString(tenantId),
          userIdentifier,
          request.cycleId == null || request.cycleId.isBlank() ? null : UUID.fromString(request.cycleId),
          request.customerName,
          request.resumeStage,
          request.serviceId,
          request.serviceName,
          request.professionalId,
          request.professionalName,
          request.date,
          request.time,
          request.assistantLastPrompt);
      return Map.of("status", "OK", "tenantId", tenantId, "userIdentifier", userIdentifier);
    } catch (IllegalArgumentException e) {
      return Map.of("status", "ERROR", "message", "UUID invalido: " + e.getMessage());
    }
  }

  @DELETE
  @Path("/admin/cache")
  public Map<String, Object> invalidateCache(@QueryParam("tenantId") String tenantId) {
    if (tenantId == null || tenantId.isBlank()) {
      return Map.of("status", "ERROR", "message", "tenantId e obrigatorio");
    }
    agentSystemPromptBuilder.invalidate(tenantId);
    return Map.of("status", "OK", "tenantId", tenantId, "message", "Cache do prompt invalidado com sucesso.");
  }

  @POST
  @Path("/admin/retrain")
  public Map<String, Object> retrain() {
    modelTrainer.forceRetrain();
    return Map.of("status", "OK", "message", "Modelos OpenNLP retreinados com sucesso.");
  }

  // Métricas de uso de LLM (chamadas, tokens, custo, latência, saúde por provedor/
  // credencial/modelo) ficam em /api/v1/assistant/admin/llm-pool/{resumo,saude,
  // metrics/providers,metrics/credentials,metrics/models} — ver LlmPoolAdminResource.
}
