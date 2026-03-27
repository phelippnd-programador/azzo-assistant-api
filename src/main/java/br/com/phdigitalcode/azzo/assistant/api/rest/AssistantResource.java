package br.com.phdigitalcode.azzo.assistant.api.rest;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import br.com.phdigitalcode.azzo.assistant.application.service.AssistantConversationService;
import br.com.phdigitalcode.azzo.assistant.domain.entity.LlmUsageDailyEntity;
import br.com.phdigitalcode.azzo.assistant.domain.repository.LlmUsageRepository;
import br.com.phdigitalcode.azzo.assistant.llm.LlmRouter;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageRequest;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageResponse;
import br.com.phdigitalcode.azzo.assistant.training.OpenNLPModelTrainer;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
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
  @Inject OpenNLPModelTrainer          modelTrainer;
  @Inject LlmRouter                    llmRouter;
  @Inject LlmUsageRepository           usageRepository;

  /**
   * Processa uma mensagem do usuário via WhatsApp.
   *
   * Headers obrigatórios:
   * - X-Tenant-Id: UUID do tenant (enviado pelo backend principal)
   * - X-User-Identifier: telefone ou e-mail do usuário
   * - X-User-Name: nome do usuário (opcional, vindo do WhatsApp profile)
   */
  @POST
  @Path("/message")
  public AssistantMessageResponse message(
      @Valid AssistantMessageRequest request,
      @HeaderParam("X-User-Identifier") String userIdentifier,
      @HeaderParam("X-User-Name") String userName) {
    return conversationService.process(request.message, userIdentifier, userName);
  }

  /**
   * Força o retreinamento dos modelos OpenNLP.
   * Usado após adicionar novos dados de treinamento.
   */
  @POST
  @Path("/admin/retrain")
  public Map<String, Object> retrain() {
    modelTrainer.forceRetrain();
    return Map.of("status", "OK", "message", "Modelos OpenNLP retreinados com sucesso.");
  }

  /**
   * Retorna métricas de uso diário do LLM.
   *
   * <p>Mostra o uso atual do Groq em relação ao limite diário configurado,
   * além do histórico dos últimos N dias (padrão: 7). Use junto com o
   * <a href="https://console.groq.com">Groq Console</a> para métricas detalhadas
   * de tokens e latência.</p>
   *
   * <p>Exemplo: GET /api/v1/assistant/admin/metrics?days=30</p>
   */
  @GET
  @Path("/admin/metrics")
  public Map<String, Object> metrics(@QueryParam("days") Integer days) {
    int lookbackDays = (days != null && days > 0 && days <= 90) ? days : 7;

    int todayGroq   = llmRouter.getDailyGroqCount();
    int todayOllama = usageRepository.getCount(LocalDate.now(), "OLLAMA");
    int dailyLimit  = llmRouter.getGroqDailyLimit();

    List<LlmUsageDailyEntity> history = usageRepository.getRecentUsage(lookbackDays);

    // Agrupa por data para facilitar leitura no frontend
    Map<String, Map<String, Integer>> historyByDate = history.stream()
        .collect(Collectors.groupingBy(
            e -> e.usageDate.toString(),
            LinkedHashMap::new,
            Collectors.toMap(e -> e.provider, e -> e.requestCount)
        ));

    Map<String, Object> today = new LinkedHashMap<>();
    today.put("groq_requests",    todayGroq);
    today.put("groq_limit",       dailyLimit);
    today.put("groq_remaining",   Math.max(0, dailyLimit - todayGroq));
    today.put("groq_usage_pct",   dailyLimit > 0 ? Math.round((todayGroq * 100.0) / dailyLimit) : 0);
    today.put("ollama_requests",  todayOllama);
    today.put("total_requests",   todayGroq + todayOllama);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("today",            today);
    result.put("circuit_breaker",  llmRouter.getCircuitBreakerStatus());
    result.put("history_days",     lookbackDays);
    result.put("history",          historyByDate);
    result.put("groq_console",     "https://console.groq.com/usage");

    return result;
  }
}
