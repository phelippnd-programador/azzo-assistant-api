package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import br.com.phdigitalcode.azzo.assistant.infrastructure.tenant.ContextoTenant;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Ponto de entrada do assistente: resolve o usuário/tenant, serializa por conversa,
 * carrega/persiste o estado e delega o atendimento da mensagem para a implementação de
 * {@link HandleMessage} adequada ({@code AgentMessageHandler} quando
 * {@code assistant.agent.enabled=true}, senão {@code LegacyMessageHandler}). A lógica de
 * condução da conversa vive nas implementações de {@link HandleMessage}; aqui ficam
 * apenas a orquestração de infraestrutura (lock, TX curtas de estado) e o mapeamento da
 * resposta da API.
 */
@ApplicationScoped
public class AssistantConversationService {

  private static final Pattern CENTS_CURRENCY_PATTERN = Pattern.compile("R\\$(\\d{3,})\\b");

  @Inject AssistantDomainService domainService;
  @Inject ConversationStateManager stateManager;
  @Inject ConversationLockManager lockManager;
  @Inject ContextoTenant contextoTenant;

  @Inject AgentMessageHandler agentHandler;
  @Inject LegacyMessageHandler legacyHandler;

  @ConfigProperty(name = "assistant.conversation.ttl-minutes", defaultValue = "480")
  long ttlMinutes;
  @ConfigProperty(name = "assistant.agent.enabled", defaultValue = "false")
  boolean agentEnabled;

  // Sem @Transactional aqui — chamadas ao LLM (lentas) não podem segurar uma transação JTA aberta.
  // As operações de DB são delegadas ao ConversationStateManager que abre transações curtas.
  public AssistantMessageResponse process(String rawMessage, String explicitUserIdentifier, String explicitUserName) {
    if (rawMessage == null || rawMessage.isBlank()) {
      throw new IllegalArgumentException("message obrigatoria");
    }

    UUID tenantId = contextoTenant.obterTenantIdOuFalhar();
    String tenantIdStr = tenantId.toString();
    String userIdentifier = resolveUserIdentifier(explicitUserIdentifier);
    String userName = resolveUserName(explicitUserName);
    if (userName == null) {
      userName = domainService.resolveRegisteredCustomerName(tenantIdStr, userIdentifier).orElse(null);
    }

    // Serializa por tenant+telefone: evita que duas mensagens quase simultâneas do
    // mesmo cliente carreguem o mesmo estado em paralelo (TX de load curta) e, minutos
    // depois, uma sobrescreva o stateJson salvo pela outra ("lost update"), ou que ambas
    // não encontrem conversa ativa e criem linhas de estado duplicadas. O lock cobre
    // também a chamada ao LLM (lenta, 30-120s) — ver ConversationLockManager para a
    // limitação conhecida (só serializa dentro desta instância/réplica).
    String lockKey = tenantIdStr + ":" + userIdentifier;
    // userName é reatribuído acima (linha ~112), então precisa de uma cópia
    // efetivamente final para ser capturada pela lambda.
    String resolvedUserName = userName;
    return lockManager.withLock(lockKey,
        () -> processLocked(rawMessage, tenantId, tenantIdStr, userIdentifier, resolvedUserName));
  }

  private AssistantMessageResponse processLocked(String rawMessage, UUID tenantId, String tenantIdStr,
      String userIdentifier, String userName) {
    // TX 1: carrega estado (< 50ms)
    Instant threshold = Instant.now().minus(Duration.ofMinutes(ttlMinutes));
    ConversationStateEntity entity = stateManager.loadOrCreate(tenantId, userIdentifier, threshold);

    ConversationData data = stateManager.parseState(entity.stateJson);
    // Mantém userIdentifier sempre atualizado no estado (usado nas notificações)
    data.userIdentifier = userIdentifier;
    clearManualInterventionSignal(data);
    if ((data.customerName == null || data.customerName.isBlank()) && userName != null) {
      data.customerName = userName;
    }

    // Sem TX aberta aqui: o LLM pode demorar 30-120s sem risco de timeout JTA.
    // Seleciona a estratégia de atendimento conforme a flag de agente.
    HandleMessage handler = agentEnabled ? agentHandler : legacyHandler;
    String reply = handler.handle(data, rawMessage, userIdentifier, tenantIdStr);
    reply = normalizeCurrencyDisplay(reply);

    // TX 2: persiste resultado (< 50ms)
    if (shouldDeleteConversationState(data)) {
      stateManager.delete(entity);
    } else {
      stateManager.save(entity, stateManager.toJson(data));
    }

    AssistantMessageResponse response = new AssistantMessageResponse();
    response.reply = reply;
    response.stage = data.stage;
    response.slots = new LinkedHashMap<>();
    response.slots.put("serviceId", data.serviceId);
    response.slots.put("serviceName", data.serviceName);
    response.slots.put("customerName", data.customerName);
    response.slots.put("professionalId", data.professionalId);
    response.slots.put("professionalName", data.professionalName);
    response.slots.put("date", data.date != null ? data.date.toString() : null);
    response.slots.put("preferredPeriod", data.preferredPeriod != null ? data.preferredPeriod.label() : null);
    response.slots.put("time", data.time);
    response.slots.put("professionalOptions", data.professionalOptionNames);
    response.slots.put("appointmentOptions", data.appointmentOptionLabels);
    response.slots.put("availableTimeOptions", data.availableTimeOptions);
    response.slots.put("appointmentId", data.appointmentId);
    response.slots.put("sourceAppointmentId", data.sourceAppointmentId);
    AbstractMessageHandler.BookingLeadSignals bookingLead =
        agentHandler.detectBookingLeadSignals(rawMessage, data, tenantIdStr);
    response.slots.put("bookingLeadDetected", bookingLead.detected);
    response.slots.put("bookingLeadServiceId", bookingLead.serviceId);
    response.slots.put("bookingLeadServiceName", bookingLead.serviceName);
    response.slots.put("bookingLeadDate", bookingLead.date);
    response.slots.put("bookingLeadTime", bookingLead.time);
    response.slots.put("reactivationStage", deriveReactivationStage(data));
    response.slots.put("manualInterventionSuggested", data.manualInterventionSuggested);
    response.slots.put("manualInterventionReason", data.manualInterventionReason);
    response.slots.put("manualInterventionAttempts", data.manualInterventionAttempts);
    return response;
  }

  private String deriveReactivationStage(ConversationData data) {
    if (data == null || data.stage == null) return null;

    return switch (data.stage) {
      case START, ASK_NAME, ASK_SERVICE -> "SERVICE_SELECTION";
      case ASK_PROFESSIONAL -> "PROFESSIONAL_SELECTION";
      case ASK_DATE, ASK_PERIOD, ASK_TIME -> "TIME_SELECTION";
      case CONFIRMATION -> "FINAL_REVIEW";
      case COMPLETED -> "COMPLETED";
      case ASK_CANCEL_APPOINTMENT,
          ASK_RESCHEDULE_APPOINTMENT,
          AWAITING_APPOINTMENT_CONFIRMATION,
          AWAITING_REACTIVATION_REPLY -> null;
    };
  }

  private String resolveUserIdentifier(String explicitUserIdentifier) {
    if (explicitUserIdentifier != null && !explicitUserIdentifier.isBlank()) {
      return explicitUserIdentifier.trim();
    }
    throw new IllegalStateException("Nao foi possivel identificar usuario da conversa: envie X-User-Identifier");
  }

  private String resolveUserName(String explicitUserName) {
    if (explicitUserName != null && !explicitUserName.isBlank()) {
      return explicitUserName.trim();
    }
    return null;
  }

  private void clearManualInterventionSignal(ConversationData data) {
    if (data == null) return;
    data.manualInterventionSuggested = false;
    data.manualInterventionReason = null;
    data.manualInterventionAttempts = null;
  }

  private boolean shouldDeleteConversationState(ConversationData data) {
    if (data == null) return true;
    return data.stage == ConversationStage.START
        && data.serviceId == null
        && data.professionalId == null
        && data.date == null
        && data.time == null
        && data.appointmentId == null
        && data.sourceAppointmentId == null
        && data.serviceName == null
        && data.professionalName == null
        && data.customerName == null
        && data.preferredPeriod == null
        && data.professionalOptionIds.isEmpty()
        && data.professionalOptionNames.isEmpty()
        && data.availableTimeOptions.isEmpty()
        && data.appointmentOptionIds.isEmpty()
        && data.appointmentOptionLabels.isEmpty();
  }

  private String normalizeCurrencyDisplay(String reply) {
    if (reply == null || reply.isBlank()) return reply;

    Matcher matcher = CENTS_CURRENCY_PATTERN.matcher(reply);
    StringBuffer sb = new StringBuffer();
    while (matcher.find()) {
      String digits = matcher.group(1);
      BigDecimal reais = new BigDecimal(digits).movePointLeft(2);
      String formatted = "R$ " + String.format(Locale.ROOT, "%.2f", reais).replace('.', ',');
      matcher.appendReplacement(sb, Matcher.quoteReplacement(formatted));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }
}
