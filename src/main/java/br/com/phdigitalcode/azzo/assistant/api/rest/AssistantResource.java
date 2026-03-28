package br.com.phdigitalcode.azzo.assistant.api.rest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import br.com.phdigitalcode.azzo.assistant.application.service.AssistantConversationService;
import br.com.phdigitalcode.azzo.assistant.application.service.ConversationStateManager;
import br.com.phdigitalcode.azzo.assistant.domain.entity.LlmUsageDailyEntity;
import br.com.phdigitalcode.azzo.assistant.domain.repository.LlmUsageRepository;
import br.com.phdigitalcode.azzo.assistant.llm.AgentSystemPromptBuilder;
import br.com.phdigitalcode.azzo.assistant.llm.LlmRouter;
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

@Path("/api/v1/assistant")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AssistantResource {

  @Inject AssistantConversationService conversationService;
  @Inject OpenNLPModelTrainer modelTrainer;
  @Inject LlmRouter llmRouter;
  @Inject LlmUsageRepository usageRepository;
  @Inject AgentSystemPromptBuilder agentSystemPromptBuilder;
  @Inject ConversationStateManager stateManager;

  @POST
  @Path("/message")
  public AssistantMessageResponse message(
      @Valid AssistantMessageRequest request,
      @HeaderParam("X-User-Identifier") String userIdentifier,
      @HeaderParam("X-User-Name") String userName) {
    return conversationService.process(request.message, userIdentifier, userName);
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

  @GET
  @Path("/admin/metrics")
  public Map<String, Object> metrics(@QueryParam("days") Integer days) {
    int lookbackDays = (days != null && days > 0 && days <= 90) ? days : 7;

    int todayGroq = llmRouter.getDailyGroqCount();
    int todayOllama = usageRepository.getCount(LocalDate.now(), "OLLAMA");
    int dailyLimit = llmRouter.getGroqDailyLimit();

    List<LlmUsageDailyEntity> history = usageRepository.getRecentUsage(lookbackDays);
    Map<String, Map<String, Integer>> historyByDate = history.stream()
        .collect(Collectors.groupingBy(
            e -> e.usageDate.toString(),
            LinkedHashMap::new,
            Collectors.toMap(e -> e.provider, e -> e.requestCount)));

    Map<String, Object> today = new LinkedHashMap<>();
    today.put("groq_requests", todayGroq);
    today.put("groq_limit", dailyLimit);
    today.put("groq_remaining", Math.max(0, dailyLimit - todayGroq));
    today.put("groq_usage_pct", dailyLimit > 0 ? Math.round((todayGroq * 100.0) / dailyLimit) : 0);
    today.put("ollama_requests", todayOllama);
    today.put("total_requests", todayGroq + todayOllama);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("today", today);
    result.put("circuit_breaker", llmRouter.getCircuitBreakerStatus());
    result.put("history_days", lookbackDays);
    result.put("history", historyByDate);
    result.put("groq_console", "https://console.groq.com/usage");
    return result;
  }
}
