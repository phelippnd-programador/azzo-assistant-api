package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.phdigitalcode.azzo.assistant.classifier.OpenNLPIntentClassifier;
import br.com.phdigitalcode.azzo.assistant.dialogue.ChatMessage;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.llm.AgentSystemPromptBuilder;
import br.com.phdigitalcode.azzo.assistant.llm.LlmBookingAgent;
import br.com.phdigitalcode.azzo.assistant.llm.OllamaDateEnricher;
import br.com.phdigitalcode.azzo.assistant.llm.OllamaIntentService;
import br.com.phdigitalcode.azzo.assistant.llm.OllamaResponseService;
import br.com.phdigitalcode.azzo.assistant.llm.OllamaSlotExtractor;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import br.com.phdigitalcode.azzo.assistant.dialogue.TimePeriod;
import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import br.com.phdigitalcode.azzo.assistant.domain.repository.ConversationStateRepository;
import br.com.phdigitalcode.azzo.assistant.extractor.ProfessionalNameFinder;
import br.com.phdigitalcode.azzo.assistant.extractor.ServiceNameFinder;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ProfissionalDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ServicoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.tenant.ContextoTenant;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageResponse;
import br.com.phdigitalcode.azzo.assistant.model.IntentPrediction;
import br.com.phdigitalcode.azzo.assistant.model.IntentType;
import br.com.phdigitalcode.azzo.assistant.util.DateTimeRegexExtractor;
import br.com.phdigitalcode.azzo.assistant.util.TextNormalizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.enterprise.inject.Instance;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AssistantConversationService {

  private static final Logger LOG = Logger.getLogger(AssistantConversationService.class);

  @Inject OpenNLPIntentClassifier intentClassifier;
  @Inject OllamaIntentService ollamaIntentService;
  @Inject OllamaDateEnricher ollamaDateEnricher;
  @Inject OllamaResponseService ollamaResponseService;
  @Inject Instance<OllamaSlotExtractor> ollamaSlotExtractorInstance;
  @Inject ServiceNameFinder serviceNameFinder;
  @Inject ProfessionalNameFinder professionalNameFinder;
  @Inject AssistantDomainService domainService;
  @Inject ConversationStateRepository stateRepository;
  @Inject ConversationStateManager stateManager;
  @Inject ContextoTenant contextoTenant;
  @Inject ObjectMapper objectMapper;
  @Inject AgentSystemPromptBuilder agentSystemPromptBuilder;
  @Inject LlmBookingAgent llmBookingAgent;

  @ConfigProperty(name = "assistant.conversation.ttl-minutes", defaultValue = "120")
  long ttlMinutes;
  @ConfigProperty(name = "assistant.greeting-zone", defaultValue = "America/Sao_Paulo")
  String greetingZone;
  @ConfigProperty(name = "assistant.intent.min-confidence", defaultValue = "0.62")
  double minIntentConfidence;
  @ConfigProperty(name = "assistant.ollama.min-confidence", defaultValue = "0.75")
  double ollamaMinConfidence;
  @ConfigProperty(name = "assistant.agent.enabled", defaultValue = "false")
  boolean agentEnabled;
  @ConfigProperty(name = "assistant.llm.max-input-chars", defaultValue = "600")
  int llmMaxInputChars;
  @ConfigProperty(name = "assistant.llm.short-response-max-tokens", defaultValue = "48")
  int shortResponseMaxTokens;

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

    // Sem TX aberta aqui: o LLM pode demorar 30-120s sem risco de timeout JTA
    String reply = agentEnabled
        ? handleMessageAgent(data, rawMessage, userIdentifier, tenantIdStr)
        : handleMessage(data, rawMessage, userIdentifier, tenantIdStr);

    // TX 2: persiste resultado (< 50ms)
    if (shouldDeleteConversationState(data)) {
      stateManager.delete(entity);
    } else {
      stateManager.save(entity, stateManager.toJson(data));
    }

    AssistantMessageResponse response = new AssistantMessageResponse();
    response.reply = reply;
    response.stage = data.stage.name();
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
    BookingLeadSignals bookingLead = detectBookingLeadSignals(rawMessage, data, tenantIdStr);
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

  // ─── NOVO: LLM como agente principal ────────────────────────────────────────

  /**
   * Novo fluxo orientado a LLM: o modelo conduz toda a conversa com contexto
   * completo do salão. Java executa as ações detectadas nos action tokens.
   */
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

  private String handleMessageAgent(ConversationData data, String rawMessage,
      String userIdentifier, String tenantId) {

    // Confirmação de presença via lembrete automático — tratamento determinístico,
    // sem envolver o LLM. Tem prioridade sobre qualquer outro estágio.
    if (data.stage == ConversationStage.AWAITING_APPOINTMENT_CONFIRMATION) {
      String normalized = TextNormalizer.normalize(rawMessage);
      return handleReminderConfirmation(data, normalized, userIdentifier, tenantId);
    }
    if (data.stage == ConversationStage.AWAITING_REACTIVATION_REPLY) {
      String normalized = TextNormalizer.normalize(rawMessage);
      return handleReactivationReply(data, rawMessage, normalized, userIdentifier, tenantId);
    }

    // Conversa anterior finalizada → limpa slots para nova sessão, mas mantém nome do cliente
    if (data.stage == ConversationStage.COMPLETED) {
      LOG.debugf("[Agent] Stage=COMPLETED — resetando slots para nova conversa");
      String savedName = data.customerName;
      data.reset();
      data.customerName = savedName; // mantém o nome para não precisar perguntar de novo
    }

    String normalized = TextNormalizer.normalize(rawMessage);

    // Limita o histórico a 30 mensagens para não estourar o contexto do LLM
    if (data.chatHistory.size() > 30) {
      List<ChatMessage> kept = new ArrayList<>(data.chatHistory.subList(
          Math.max(0, data.chatHistory.size() - 25), data.chatHistory.size()));
      data.chatHistory.clear();
      data.chatHistory.addAll(kept);
    }

    // Resolve datas relativas em Java antes de enviar ao LLM (modelos 8B erram esse cálculo)
    String contextualMessage = contextualizeAgentSelection(data, rawMessage, normalized);
    String enrichedMessage = enrichDatesInMessage(contextualMessage);
    String compactedMessage = compactMessageForLlm(enrichedMessage);
    LlmBookingAgent.AgentChatOptions chatOptions = buildAgentChatOptions(data, rawMessage);

    String systemPrompt = agentSystemPromptBuilder.build(tenantId);
    // Passa activeProvider para sticky routing — null = nova conversa, router decide
    LlmBookingAgent.AgentResult result = llmBookingAgent.chat(
        systemPrompt, data.chatHistory, compactedMessage, data.activeProvider, chatOptions);

    // Persiste o provider escolhido para manter sticky durante toda a conversa
    if (result.providerUsed() != null) {
      data.activeProvider = result.providerUsed();
    }

    // Safety net: se o LLM pediu confirmação, o cliente confirmou, mas o LLM esqueceu de
    // emitir [CRIAR_AGENDAMENTO], re-chama com hint explícito (1 tentativa).
    if (result.actions().isEmpty()
        && isConfirmationPending(data.chatHistory)
        && isAffirmativeResponse(rawMessage)) {
      LOG.infof("[Agent] Confirmação detectada sem action token — re-chamando LLM com hint");
      List<ChatMessage> tempHistory = new ArrayList<>(data.chatHistory);
      tempHistory.add(new ChatMessage("user", compactedMessage));
      tempHistory.add(new ChatMessage("tool",
          "[Sistema: o cliente acabou de confirmar o agendamento. "
          + "Emita OBRIGATORIAMENTE [CRIAR_AGENDAMENTO:...] com todos os dados coletados na conversa. "
          + "Sem esse token, nenhum agendamento será criado no sistema.]"));
      LlmBookingAgent.AgentResult retry = llmBookingAgent.chat(
          systemPrompt, tempHistory, "", data.activeProvider);
      if (retry.hasAction("CRIAR_AGENDAMENTO")) {
        LOG.infof("[Agent] Re-chamada retornou CRIAR_AGENDAMENTO — usando resultado do retry");
        result = retry;
      } else {
        LOG.warnf("[Agent] Re-chamada ainda sem CRIAR_AGENDAMENTO — seguindo com resultado original");
      }
    }

    // Processa ações — max 1 round-trip para evitar loops
    String finalReply = processActions(result, data, userIdentifier, tenantId, systemPrompt);

    // Grava no histórico para próximos turnos (usa mensagem enriquecida para consistência)
    data.chatHistory.add(new ChatMessage("user", compactedMessage));
    data.chatHistory.add(new ChatMessage("assistant", finalReply));

    return finalReply;
  }

  /**
   * No modo agent, respostas curtas como "7" ou "2" precisam ser transformadas em
   * contexto explícito antes de ir ao LLM. Sem isso, o modelo recebe apenas um ordinal
   * solto e pode falhar em conectar a escolha à lista apresentada no turno anterior.
   */
  private String contextualizeAgentSelection(ConversationData data, String rawMessage, String normalized) {
    if (rawMessage == null || rawMessage.isBlank()) {
      return rawMessage;
    }

    if (!data.availableTimeOptions.isEmpty()) {
      OptionalInt selectedTimeIndex = parseOrdinalSelection(rawMessage, data.availableTimeOptions.size());
      if (selectedTimeIndex.isPresent()) {
        String selectedTime = data.availableTimeOptions.get(selectedTimeIndex.getAsInt());
        LOG.debugf("[Agent] Reescrevendo escolha ordinal de horário: input=%s horario=%s", rawMessage, selectedTime);
        return rawMessage + "\n[Sistema: o cliente escolheu o horário " + selectedTime
            + " da lista de opções já apresentada.]";
      }

      Optional<String> extractedTime = DateTimeRegexExtractor.extractTime(rawMessage);
      if (extractedTime.isPresent()) {
        String normalizedTime = normalizeTime(extractedTime.get());
        if (data.availableTimeOptions.contains(normalizedTime)) {
          LOG.debugf("[Agent] Reescrevendo escolha literal de horário: input=%s horario=%s", rawMessage, normalizedTime);
          return rawMessage + "\n[Sistema: o cliente escolheu o horário " + normalizedTime
              + " da lista de opções já apresentada.]";
        }
      }
    }

    if (data.professionalId == null && !data.professionalOptionNames.isEmpty()) {
      OptionalInt selectedProfessionalIndex = parseOrdinalSelection(rawMessage, data.professionalOptionNames.size());
      if (selectedProfessionalIndex.isPresent()) {
        String selectedProfessional = data.professionalOptionNames.get(selectedProfessionalIndex.getAsInt());
        LOG.debugf("[Agent] Reescrevendo escolha ordinal de profissional: input=%s profissional=%s",
            rawMessage, selectedProfessional);
        return rawMessage + "\n[Sistema: o cliente escolheu o profissional " + selectedProfessional
            + " da lista de opções já apresentada.]";
      }
    }

    if (!data.appointmentOptionLabels.isEmpty()) {
      OptionalInt selectedAppointmentIndex = parseOrdinalSelection(rawMessage, data.appointmentOptionLabels.size());
      if (selectedAppointmentIndex.isPresent()) {
        String selectedAppointment = data.appointmentOptionLabels.get(selectedAppointmentIndex.getAsInt());
        LOG.debugf("[Agent] Reescrevendo escolha ordinal de agendamento: input=%s agendamento=%s",
            rawMessage, selectedAppointment);
        return rawMessage + "\n[Sistema: o cliente escolheu a opção " + selectedAppointment
            + " da lista de agendamentos já apresentada.]";
      }
    }

    // Mantém a mensagem original quando não há escolha estruturada para contextualizar.
    return rawMessage;
  }

  /**
   * Executa as ações detectadas no resultado do LLM e, se necessário,
   * re-chama o LLM com o resultado injetado no contexto.
   */
  private String processActions(LlmBookingAgent.AgentResult result, ConversationData data,
      String userIdentifier, String tenantId, String systemPrompt) {

    if (result.actions().isEmpty()) {
      return result.text();
    }

    // CONSULTAR_HORARIOS — busca horários e re-chama LLM com o resultado
    LlmBookingAgent.AgentAction slotsAction = result.firstAction("CONSULTAR_HORARIOS");
    if (slotsAction != null) {
      String toolResult = executeConsultarHorarios(slotsAction, data, tenantId);
      // Injeta o resultado como contexto e pede ao LLM para apresentar ao cliente
      String toolContext = "[Sistema] Horários disponíveis:\n" + toolResult
          + "\nApresente esses horários ao cliente e peça para escolher pelo número ou digitando o horário.";
      List<ChatMessage> tempHistory = new ArrayList<>(data.chatHistory);
      tempHistory.add(new ChatMessage("assistant", result.text()));
      LlmBookingAgent.AgentResult followUp = llmBookingAgent.chat(
          systemPrompt, tempHistory, toolContext, data.activeProvider);
      return followUp.text().isBlank() ? result.text() : followUp.text();
    }

    // CRIAR_AGENDAMENTO — cria o agendamento e re-chama LLM com confirmação
    LlmBookingAgent.AgentAction bookAction = result.firstAction("CRIAR_AGENDAMENTO");
    if (bookAction != null) {
      String toolResult = executeCriarAgendamento(bookAction, data, userIdentifier, tenantId);
      List<ChatMessage> tempHistory = new ArrayList<>(data.chatHistory);
      tempHistory.add(new ChatMessage("assistant", result.text()));
      tempHistory.add(new ChatMessage("user", "[Sistema] " + toolResult
          + "\nConfirme o agendamento ao cliente com um resumo amigável."));
      LlmBookingAgent.AgentResult followUp = llmBookingAgent.chat(
          systemPrompt, tempHistory, "", data.activeProvider);
      return followUp.text().isBlank() ? toolResult : followUp.text();
    }

    // CANCELAR_AGENDAMENTO
    LlmBookingAgent.AgentAction cancelAction = result.firstAction("CANCELAR_AGENDAMENTO");
    if (cancelAction != null) {
      return executeCancelarAgendamento(cancelAction, data, userIdentifier, tenantId);
    }

    return result.text();
  }

  private String executeConsultarHorarios(LlmBookingAgent.AgentAction action,
      ConversationData data, String tenantId) {
    try {
      String profAlias = action.param("prof");
      String svcAlias = action.param("svc");
      String dateStr = action.param("date");

      UUID professionalId = profAlias != null
          ? agentSystemPromptBuilder.resolveProfessionalId(tenantId, profAlias).orElse(null)
          : data.professionalId;
      UUID serviceId = svcAlias != null
          ? agentSystemPromptBuilder.resolveServiceId(tenantId, svcAlias).orElse(null)
          : data.serviceId;
      LocalDate date = dateStr != null ? LocalDate.parse(dateStr) : data.date;

      if (professionalId == null || date == null) {
        return "Não consegui identificar o profissional ou a data. Tente novamente.";
      }

      // Guarda nos slots para uso posterior
      if (professionalId != null) data.professionalId = professionalId;
      if (serviceId != null) data.serviceId = serviceId;
      if (date != null) data.date = date;

      List<String> slots = domainService.suggestTimes(tenantId, professionalId, date, serviceId, null);
      if (slots.isEmpty()) {
        return "Sem horários disponíveis nessa data.";
      }
      data.availableTimeOptions = new ArrayList<>(slots);
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < slots.size(); i++) {
        sb.append(i + 1).append(". ").append(slots.get(i)).append("\n");
      }
      return sb.toString().trim();
    } catch (Exception e) {
      LOG.warnf("[Agent] Erro ao consultar horários: %s", e.getMessage());
      return "Erro ao consultar horários. Tente outra data.";
    }
  }

  private String executeCriarAgendamento(LlmBookingAgent.AgentAction action,
      ConversationData data, String userIdentifier, String tenantId) {
    try {
      String profAlias = action.param("prof");
      String svcAlias = action.param("svc");
      String dateStr = action.param("date");
      String time = action.param("time");
      String customerName = action.param("customer");

      UUID professionalId = profAlias != null
          ? agentSystemPromptBuilder.resolveProfessionalId(tenantId, profAlias).orElse(data.professionalId)
          : data.professionalId;
      UUID serviceId = svcAlias != null
          ? agentSystemPromptBuilder.resolveServiceId(tenantId, svcAlias).orElse(data.serviceId)
          : data.serviceId;
      LocalDate date = dateStr != null ? LocalDate.parse(dateStr) : data.date;

      if (customerName != null && !customerName.isBlank()) data.customerName = customerName.trim();
      if (professionalId != null) data.professionalId = professionalId;
      if (serviceId != null) data.serviceId = serviceId;
      if (date != null) data.date = date;
      if (time != null && !time.isBlank()) data.time = time;

      if (data.serviceId == null || data.professionalId == null || data.date == null || data.time == null) {
        return "Faltam informações para criar o agendamento (serviço, profissional, data ou horário).";
      }

      // Validação hard: nunca criar agendamento retroativo
      if (data.date.isBefore(LocalDate.now())) {
        LOG.warnf("[Agent] Tentativa de agendamento retroativo bloqueada: date=%s", data.date);
        data.date = null;
        return "[Sistema] Data inválida — essa data já passou. Informe ao cliente e peça uma data a partir de hoje.";
      }

      UUID appointmentId = domainService.createPendingAppointment(
          tenantId, data.serviceId, data.professionalId, data.date, data.time,
          userIdentifier, data.customerName);
      domainService.confirmAppointment(tenantId, appointmentId, userIdentifier);
      data.appointmentId = appointmentId;
      data.stage = ConversationStage.COMPLETED;

      return String.format("Agendamento criado com sucesso! ID: %s | Data: %s às %s",
          appointmentId, data.date, data.time);
    } catch (Exception e) {
      LOG.warnf("[Agent] Erro ao criar agendamento: %s", e.getMessage());
      return "Não consegui criar o agendamento. Verifique se o horário ainda está disponível.";
    }
  }

  private String executeCancelarAgendamento(LlmBookingAgent.AgentAction action,
      ConversationData data, String userIdentifier, String tenantId) {
    try {
      String appointmentIdStr = action.param("appointment_id");
      if (appointmentIdStr == null || appointmentIdStr.isBlank()) {
        return "Informe o ID do agendamento para cancelar.";
      }
      UUID appointmentId = UUID.fromString(appointmentIdStr);
      domainService.cancelAppointmentForUser(tenantId, userIdentifier, appointmentId);
      data.stage = ConversationStage.COMPLETED;
      return "Agendamento cancelado com sucesso! Se quiser marcar outro, é só falar. 😊";
    } catch (Exception e) {
      LOG.warnf("[Agent] Erro ao cancelar agendamento: %s", e.getMessage());
      return "Não consegui cancelar o agendamento. 😕";
    }
  }

  // ─── LEGADO: máquina de estados Java ─────────────────────────────────────────

  private String handleMessage(ConversationData data, String rawMessage, String userIdentifier, String tenantId) {
    String normalized = TextNormalizer.normalize(rawMessage);

    // Confirmação de presença via lembrete automático — tratamento determinístico,
    // independente do fluxo de booking. Tem prioridade sobre qualquer outro estágio.
    if (data.stage == ConversationStage.AWAITING_APPOINTMENT_CONFIRMATION) {
      return handleReminderConfirmation(data, normalized, userIdentifier, tenantId);
    }
    if (data.stage == ConversationStage.AWAITING_REACTIVATION_REPLY) {
      return handleReactivationReply(data, rawMessage, normalized, userIdentifier, tenantId);
    }

    boolean prioritizeSlotInput = shouldPrioritizeSlotInput(data, rawMessage, normalized);
    if (!prioritizeSlotInput && isDeterministicGreeting(normalized) && data.stage != ConversationStage.CONFIRMATION) {
      return greetingReplyForCurrentStage(data, tenantId);
    }

    IntentPrediction intentPrediction = intentClassifier.classifyWithConfidence(rawMessage);
    IntentType intent = intentPrediction.intent;
    String correctionReply = handleCorrections(data, normalized, tenantId);
    if (correctionReply != null) {
      return correctionReply;
    }

    // Enriquecimento via Ollama: tenta melhorar a classificação quando OpenNLP tem baixa confiança
    intentPrediction = enrichIntentWithOllama(intentPrediction, rawMessage, data.stage);
    intent = intentPrediction.intent;

    String disambiguationReply = handleLowConfidenceIntent(data, intentPrediction, prioritizeSlotInput);
    if (disambiguationReply != null) {
      return disambiguationReply;
    }

    if (data.stage == ConversationStage.CONFIRMATION) {
      // Confirmação: determinístico primeiro (sem Ollama), depois fallback Ollama
      if (DateTimeRegexExtractor.isAffirmative(normalized) || isInformalAffirmative(normalized)) {
        domainService.confirmAppointment(tenantId, data.appointmentId, data.userIdentifier);
        if (data.sourceAppointmentId != null && !data.sourceAppointmentId.equals(data.appointmentId)) {
          domainService.cancelAppointmentForUser(tenantId, userIdentifier, data.sourceAppointmentId);
        }
        data.reset();
        return "Agendamento confirmado! ✅ Te esperamos lá. 😊";
      }
      if (DateTimeRegexExtractor.isNegative(normalized) || isInformalNegative(normalized)) {
        domainService.cancelAppointment(tenantId, data.appointmentId, data.userIdentifier);
        data.reset();
        return "Tudo bem! Cancelei esse agendamento. Quando quiser marcar de novo, é só chamar. 👋";
      }
      // Fallback Ollama: cobre expressões ainda mais informais ("pode!", "fecha!", "não quero mais")
      Optional<Boolean> ollamaConfirm = ollamaSlotExtractor()
          .flatMap(extractor -> extractor.extractConfirmation(rawMessage));
      if (ollamaConfirm.isPresent()) {
        if (Boolean.TRUE.equals(ollamaConfirm.get())) {
          domainService.confirmAppointment(tenantId, data.appointmentId, data.userIdentifier);
          if (data.sourceAppointmentId != null && !data.sourceAppointmentId.equals(data.appointmentId)) {
            domainService.cancelAppointmentForUser(tenantId, userIdentifier, data.sourceAppointmentId);
          }
          data.reset();
          return "Agendamento confirmado! ✅ Te esperamos lá. 😊";
        } else {
          domainService.cancelAppointment(tenantId, data.appointmentId, data.userIdentifier);
          data.reset();
          return "Tudo bem! Cancelei esse agendamento. Quando quiser marcar de novo, é só chamar. 👋";
        }
      }
      return "Não entendi direito. Manda *SIM* pra confirmar ou *NÃO* pra cancelar, tá? 😊";
    }

    if (!prioritizeSlotInput && intent == IntentType.GREETING && data.stage != ConversationStage.CONFIRMATION) {
      return greetingReplyForCurrentStage(data, tenantId);
    }
    if (!prioritizeSlotInput && intent == IntentType.LIST) {
      data.stage = ConversationStage.START;
      return domainService.listUpcomingForUser(tenantId, userIdentifier);
    }
    if (!prioritizeSlotInput && intent == IntentType.CANCEL) {
      if (!domainService.canCancelViaWhatsApp(tenantId)) {
        return "Esse salão não permite cancelamentos pelo WhatsApp agora. 😕";
      }
      return iniciarFluxoCancelamento(data, userIdentifier, tenantId);
    }
    if (!prioritizeSlotInput && intent == IntentType.RESCHEDULE) {
      if (!domainService.canRescheduleViaWhatsApp(tenantId)) {
        return "Esse salão não permite remarcações pelo WhatsApp agora. 😕";
      }
      return iniciarFluxoRemarcacao(data, userIdentifier, tenantId);
    }

    // Pergunta de preço/valor: responde com a lista de serviços incluindo preços
    if (!prioritizeSlotInput && isPriceQuery(normalized)
        && data.stage != ConversationStage.CONFIRMATION) {
      return domainService.formatServicesPromptForCustomer(tenantId, data.customerName);
    }

    if (data.stage == ConversationStage.ASK_CANCEL_APPOINTMENT) {
      if (!domainService.canCancelViaWhatsApp(tenantId)) {
        data.stage = ConversationStage.START;
        return "Esse salão não permite cancelamentos pelo WhatsApp agora. 😕";
      }

      // Aguardando confirmação do cancelamento selecionado
      if (data.pendingCancelAppointmentId != null) {
        if (DateTimeRegexExtractor.isAffirmative(normalized) || isInformalAffirmative(normalized)) {
          domainService.cancelAppointmentForUser(tenantId, userIdentifier, data.pendingCancelAppointmentId);
          data.pendingCancelAppointmentId = null;
          resetFlowKeepCustomer(data);
          data.stage = ConversationStage.COMPLETED;
          return "Prontinho! Agendamento cancelado. ✅ Se quiser marcar outro, é só falar. 😊";
        }
        if (DateTimeRegexExtractor.isNegative(normalized) || isInformalNegative(normalized)) {
          data.pendingCancelAppointmentId = null;
          resetFlowKeepCustomer(data);
          data.stage = ConversationStage.START;
          return "Tudo bem! Cancelamento desfeito. Precisa de mais alguma coisa? 😊";
        }
        return "Confirma o cancelamento? Manda *SIM* pra cancelar ou *NÃO* pra manter. 😊";
      }

      // Seleção do agendamento a cancelar
      UUID selectedAppointmentId = parseSelectedAppointmentId(data, rawMessage);
      if (selectedAppointmentId == null) {
        return "Qual você quer cancelar? Manda o número:\n" + buildNumberedAppointmentList(data.appointmentOptionLabels);
      }
      // Pede confirmação antes de cancelar
      int idx = data.appointmentOptionIds.indexOf(selectedAppointmentId.toString());
      String label = idx >= 0 ? data.appointmentOptionLabels.get(idx) : "esse agendamento";
      data.pendingCancelAppointmentId = selectedAppointmentId;
      return "Vai cancelar:\n*" + label + "*\n\nConfirma? Manda *SIM* pra cancelar ou *NÃO* pra manter. 😊";
    }

    if (data.stage == ConversationStage.ASK_RESCHEDULE_APPOINTMENT) {
      if (!domainService.canRescheduleViaWhatsApp(tenantId)) {
        data.stage = ConversationStage.START;
        return "Esse salão não permite remarcações pelo WhatsApp agora. 😕";
      }
      UUID selectedAppointmentId = parseSelectedAppointmentId(data, rawMessage);
      if (selectedAppointmentId == null) {
        return "Qual você quer remarcar? Manda o número:\n"
            + buildNumberedAppointmentList(data.appointmentOptionLabels);
      }

      AssistantDomainService.UpcomingAppointmentOption option = domainService
          .findUpcomingOptionForUser(tenantId, userIdentifier, selectedAppointmentId)
          .orElseThrow(() -> new IllegalArgumentException("Agendamento nao encontrado para remarcar"));

      clearBookingFlow(data, tenantId);
      data.sourceAppointmentId = option.appointmentId;
      data.serviceId = option.serviceId;
      data.professionalId = option.professionalId;
      data.serviceName = option.serviceName;
      data.professionalName = option.professionalName;
      data.stage = ConversationStage.ASK_DATE;
      return "Ótimo! Vamos remarcar *" + option.serviceName + "* com " + option.professionalName
          + ". Que novo dia funciona pra você? 📅";
    }

    if (data.stage == ConversationStage.START || data.stage == ConversationStage.COMPLETED) {
      if (!domainService.canScheduleViaWhatsApp(tenantId)) {
        return "Esse salão não permite novos agendamentos pelo WhatsApp agora. 😕";
      }
      data.stage = (data.customerName != null && !data.customerName.isBlank())
          ? ConversationStage.ASK_SERVICE
          : ConversationStage.ASK_NAME;
    }

    if (data.customerName == null || data.customerName.isBlank()) {
      String maybeName = extractCustomerName(rawMessage);
      if (maybeName != null) {
        data.customerName = maybeName;
        data.stage = ConversationStage.ASK_SERVICE;
        // Retorna imediatamente — a mesma mensagem do nome NÃO deve ser reutilizada
        // para resolução de serviço, evitando que o Ollama alucie um serviço a partir
        // do nome do cliente (ex: "Carlos Silva" → "Barba").
        return domainService.formatServicesPrompt(tenantId);
      } else {
        data.stage = ConversationStage.ASK_NAME;
        return "Oi! Pra começar, me conta seu nome completo. 😊";
      }
    }

    if (data.serviceId == null) {
      Optional<String> extracted = serviceNameFinder.extractFirst(rawMessage);
      Optional<ServicoDto> resolved = extracted.flatMap(n -> domainService.resolveService(tenantId, n));
      if (resolved.isEmpty()) {
        resolved = domainService.resolveService(tenantId, rawMessage);
      }
      // Fallback Ollama: identifica serviço em linguagem natural ("quero fazer as unhas")
      if (resolved.isEmpty()) {
        List<String> serviceNames = domainService.listServices(tenantId).stream()
            .map(s -> s.name).toList();
        Optional<String> ollamaService = ollamaSlotExtractor()
            .flatMap(extractor -> extractor.extractServiceName(rawMessage, serviceNames));
        if (ollamaService.isPresent()) {
          resolved = domainService.resolveService(tenantId, ollamaService.get());
        }
      }
      if (resolved.isPresent()) {
        ServicoDto service = resolved.get();
        data.serviceId = UUID.fromString(service.id);
        data.serviceName = service.name;
        data.stageAttempts = 0;
        data.professionalOptionIds.clear();
        data.professionalOptionNames.clear();
        data.stage = ConversationStage.ASK_PROFESSIONAL;
      } else {
        data.stage = ConversationStage.ASK_SERVICE;
        data.stageAttempts++;
        return withHandoffIfNeeded(data, domainService.formatServicesPromptForCustomer(tenantId, data.customerName));
      }
    }

    if (data.professionalId == null) {
      if (!data.professionalOptionIds.isEmpty()) {
        OptionalInt index = parseOrdinalSelection(rawMessage, data.professionalOptionIds.size());
        if (index.isPresent()) {
          int selected = index.getAsInt();
          data.professionalId = UUID.fromString(data.professionalOptionIds.get(selected));
          data.professionalName = data.professionalOptionNames.get(selected);
          if (selected < data.professionalOptionSpecialtyNames.size()) {
            data.professionalSpecialtyName = data.professionalOptionSpecialtyNames.get(selected);
          }
        }
      }

      if (data.professionalId == null) {
        Optional<String> extracted = professionalNameFinder.extractFirst(rawMessage);
        Optional<ProfissionalDto> resolved = extracted.flatMap(n -> domainService.resolveProfessional(tenantId, n, data.serviceId));
        if (resolved.isEmpty()) {
          resolved = domainService.resolveProfessional(tenantId, rawMessage, data.serviceId);
        }
        // Fallback Ollama: identifica profissional em linguagem natural ("pode ser a Maria")
        if (resolved.isEmpty() && !data.professionalOptionNames.isEmpty()) {
          Optional<String> ollamaProf = ollamaSlotExtractor()
              .flatMap(extractor -> extractor.extractProfessionalName(rawMessage, data.professionalOptionNames));
          if (ollamaProf.isPresent()) {
            resolved = domainService.resolveProfessional(tenantId, ollamaProf.get(), data.serviceId);
          }
        }
        if (resolved.isPresent()) {
          ProfissionalDto professional = resolved.get();
          data.professionalId = UUID.fromString(professional.id);
          data.professionalName = professional.name;
          data.professionalSpecialtyName = domainService.firstSpecialtyName(professional);
          data.stageAttempts = 0;
        }
      }

      if (data.professionalId == null) {
        data.stage = ConversationStage.ASK_PROFESSIONAL;
        if (!data.professionalOptionNames.isEmpty()) {
          data.stageAttempts++;
        }
        return withHandoffIfNeeded(data, buildProfessionalPrompt(data, tenantId));
      }

      data.professionalOptionIds.clear();
      data.professionalOptionNames.clear();
      data.availableTimeOptions.clear();
      data.time = null;
      data.preferredPeriod = null;
      data.date = null;
      data.stage = ConversationStage.ASK_DATE;
    }

    if (data.date == null) {
      Optional<LocalDate> date = DateTimeRegexExtractor.extractDate(rawMessage);
      if (date.isEmpty() && looksLikeTemporalExpression(rawMessage)) {
        // Fallback LLM: só chama se a mensagem parece uma expressão temporal
        // ("sexta", "semana que vem", "próximo mês", etc.).
        // Evita alucinações quando o usuário manda texto não relacionado a datas
        // (ex: nome de serviço, profissional, confirmações informais).
        date = ollamaDateEnricher.enrich(rawMessage);
      }
      if (date.isPresent()) {
        LocalDate extracted = date.get();
        if (extracted.isBefore(java.time.LocalDate.now())) {
          data.stage = ConversationStage.ASK_DATE;
          return "Essa data já passou. Me manda uma data a partir de hoje, tá? Ex: amanhã ou 25/04. 😅";
        }
        data.date = extracted;
        data.preferredPeriod = null;
        data.time = null;
        data.availableTimeOptions.clear();
        data.stage = ConversationStage.ASK_PERIOD;
      } else {
        data.stage = ConversationStage.ASK_DATE;
        return "Que dia você quer? Pode mandar tipo \"amanhã\", \"sexta\" ou uma data como 25/04. 📅";
      }
    }

    if (data.preferredPeriod == null) {
      Optional<TimePeriod> period = TimePeriod.fromText(normalized);
      // Fallback Ollama: expressões como "de manhã cedo", "pós-almoço", "fim do dia"
      if (period.isEmpty()) {
        Optional<String> ollamaPeriod = ollamaSlotExtractor()
            .flatMap(extractor -> extractor.extractTimePeriod(rawMessage));
        if (ollamaPeriod.isPresent()) {
          period = switch (ollamaPeriod.get()) {
            case "MORNING"   -> Optional.of(TimePeriod.MORNING);
            case "AFTERNOON" -> Optional.of(TimePeriod.AFTERNOON);
            case "NIGHT"     -> Optional.of(TimePeriod.NIGHT);
            default          -> Optional.empty();
          };
        }
      }
      if (period.isPresent()) {
        data.preferredPeriod = period.get();
        data.availableTimeOptions.clear();
        data.stage = ConversationStage.ASK_TIME;
      } else {
        data.stage = ConversationStage.ASK_PERIOD;
        return "Legal! Prefere de manhã, tarde ou noite? ☀️🌙";
      }
    }

    if (data.time == null) {
      Optional<TimePeriod> switchPeriod = TimePeriod.fromText(normalized);
      if (switchPeriod.isPresent() && switchPeriod.get() != data.preferredPeriod) {
        data.preferredPeriod = switchPeriod.get();
        data.availableTimeOptions.clear();
      }
      if (wantsChangeDay(normalized)) {
        data.date = null;
        data.time = null;
        data.preferredPeriod = null;
        data.availableTimeOptions.clear();
        data.stage = ConversationStage.ASK_DATE;
        return "Sem problema! Que novo dia funciona? 📅";
      }

      if (data.availableTimeOptions.isEmpty()) {
        data.availableTimeOptions = new ArrayList<>(domainService.suggestTimes(tenantId, data.professionalId, data.date, data.serviceId, data.preferredPeriod));
      }
      if (data.availableTimeOptions.isEmpty()) {
        data.stage = ConversationStage.ASK_TIME;
        return "Não tem horário vago de " + data.preferredPeriod.label()
            + " nessa data. 😕 Quer tentar outro período (manhã/tarde/noite) ou mudar o dia?";
      }

      OptionalInt index = parseOrdinalSelection(rawMessage, data.availableTimeOptions.size());
      if (index.isPresent()) {
        data.time = data.availableTimeOptions.get(index.getAsInt());
      } else {
        Optional<String> time = DateTimeRegexExtractor.extractTime(rawMessage);
        if (time.isPresent()) {
          String normalizedTime = normalizeTime(time.get());
          if (data.availableTimeOptions.contains(normalizedTime)) {
            data.time = normalizedTime;
          } else {
            data.stage = ConversationStage.ASK_TIME;
            return "Esse horário não está disponível. Escolha um dos vagos de " + data.preferredPeriod.label() + ":\n"
                + buildNumberedTimeList(data.availableTimeOptions)
                + "\nOu fala outro período ou \"trocar dia\" pra mudar. 😊";
          }
        }
      }

      if (data.time == null) {
        data.stage = ConversationStage.ASK_TIME;
        String bestSlot = data.availableTimeOptions.isEmpty() ? null : data.availableTimeOptions.get(0);
        java.util.Optional<String> llmTime = ollamaResponseService.generateTimeSlotsMessage(
            data.preferredPeriod.label(), data.availableTimeOptions, bestSlot);
        if (llmTime.isPresent()) {
          return llmTime.get();
        }
        return "Qual horário de " + data.preferredPeriod.label() + " fica bom? Escolha pelo número:\n"
            + buildNumberedTimeList(data.availableTimeOptions)
            + "\nOu fala outro período ou \"trocar dia\". 😊";
      }
    }

    // Validação extra: se a data é hoje, rejeita horários já passados (cache desatualizado)
    if (data.date != null && data.date.isEqual(LocalDate.now())) {
      try {
        LocalTime selectedTime = LocalTime.parse(data.time);
        if (selectedTime.isBefore(LocalTime.now(ZoneId.of(greetingZone)))) {
          data.time = null;
          data.availableTimeOptions.clear(); // força recarga de slots frescos
          data.stage = ConversationStage.ASK_TIME;
          List<String> freshSlots = new ArrayList<>(
              domainService.suggestTimes(tenantId, data.professionalId, data.date, data.serviceId, data.preferredPeriod));
          if (freshSlots.isEmpty()) {
            return "Esse horário já passou e não tem mais vaga hoje. 😅 Quer tentar outro dia? Manda \"trocar dia\".";
          }
          data.availableTimeOptions = freshSlots;
          return "Esse horário já passou! 😅 Escolhe um disponível ainda hoje:\n"
              + buildNumberedTimeList(data.availableTimeOptions)
              + "\nOu manda \"trocar dia\" pra escolher outra data.";
        }
      } catch (RuntimeException ignored) {
        // Parsing de hora falhou — deixa o isSlotAvailable rejeitar
      }
    }

    if (!domainService.isSlotAvailable(tenantId, data.professionalId, data.date, data.time, data.serviceId)) {
      data.time = null;
      data.stage = ConversationStage.ASK_TIME;
      data.availableTimeOptions = new ArrayList<>(domainService.suggestTimes(tenantId, data.professionalId, data.date, data.serviceId, data.preferredPeriod));
      if (data.availableTimeOptions.isEmpty()) {
        return "Esse horário ficou indisponível e não tem mais vaga nesse período. 😕 "
            + "Quer tentar outro período (manhã/tarde/noite) ou mudar o dia?";
      }
      return "Esse horário ficou indisponível. Escolha outro de " + data.preferredPeriod.label() + ":\n"
          + buildNumberedTimeList(data.availableTimeOptions);
    }

    if (data.appointmentId == null) {
      if (data.sourceAppointmentId == null && !domainService.canScheduleViaWhatsApp(tenantId)) {
        resetFlowKeepCustomer(data);
        data.stage = ConversationStage.START;
        return "Esse salão não permite novos agendamentos pelo WhatsApp agora. 😕";
      }
      if (data.sourceAppointmentId != null && !domainService.canRescheduleViaWhatsApp(tenantId)) {
        resetFlowKeepCustomer(data);
        data.stage = ConversationStage.START;
        return "Esse salão não permite remarcações pelo WhatsApp agora. 😕";
      }
      try {
        data.appointmentId = domainService.createPendingAppointment(
            tenantId,
            data.serviceId,
            data.professionalId,
            data.date,
            data.time,
            userIdentifier,
            data.customerName);
      } catch (RuntimeException e) {
        // Race condition: slot foi reservado por outro usuário entre a verificação e a criação
        LOG.warnf("Conflito ao criar agendamento (race condition): tenant=%s horario=%s erro=%s",
            tenantId, data.time, e.getMessage());
        data.time = null;
        data.stage = ConversationStage.ASK_TIME;
        data.availableTimeOptions = new ArrayList<>(domainService.suggestTimes(
            tenantId, data.professionalId, data.date, data.serviceId, data.preferredPeriod));
        if (data.availableTimeOptions.isEmpty()) {
          return "Esse horário acabou de ser reservado. 😕 Não tem mais vaga nesse período. "
              + "Quer mudar o período (manhã/tarde/noite) ou o dia?";
        }
        return "Esse horário acabou de ser reservado por outro cliente. Escolha um novo de "
            + data.preferredPeriod.label() + ":\n" + buildNumberedTimeList(data.availableTimeOptions);
      }
    }

    data.stage = ConversationStage.CONFIRMATION;
    String profDisplay = data.professionalSpecialtyName != null
        ? data.professionalName + " (" + data.professionalSpecialtyName + ")"
        : data.professionalName;
    return String.format(Locale.ROOT,
        "Tá quase! Confirma o agendamento?\n\n✂️ *%s*\n👤 %s\n📅 %s às %s\n\nResponde *SIM* pra confirmar ou *NÃO* pra cancelar.",
        data.serviceName,
        profDisplay,
        data.date,
        data.time);
  }

  private String handleCorrections(ConversationData data, String normalizedMessage, String tenantId) {
    if (wantsChangeDay(normalizedMessage)) {
      if (data.serviceId == null || data.professionalId == null) {
        return "Primeiro me fala o serviço e o profissional, aí a gente troca o dia. 😊";
      }
      cancelPendingIfExists(data, tenantId);
      data.date = null;
      data.time = null;
      data.preferredPeriod = null;
      data.availableTimeOptions.clear();
      data.stage = ConversationStage.ASK_DATE;
      return "Sem problema! Que novo dia funciona? 📅";
    }

    if (isRestartCommand(normalizedMessage)) {
      cancelPendingIfExists(data, tenantId);
      String customerName = data.customerName;
      data.reset();
      data.customerName = customerName;
      data.stage = ConversationStage.ASK_SERVICE;
      return "Tudo bem! Recomeçamos do zero. " + domainService.formatServicesPrompt(tenantId);
    }

    if (isChangeServiceCommand(normalizedMessage)) {
      cancelPendingIfExists(data, tenantId);
      data.serviceId = null;
      data.serviceName = null;
      data.professionalId = null;
      data.professionalName = null;
      data.date = null;
      data.time = null;
      data.preferredPeriod = null;
      data.appointmentId = null;
      data.sourceAppointmentId = null;
      data.professionalOptionIds.clear();
      data.professionalOptionNames.clear();
      data.availableTimeOptions.clear();
      data.stage = ConversationStage.ASK_SERVICE;
      return "Tudo bem! Qual serviço você quer? 💅";
    }

    if (isChangeProfessionalCommand(normalizedMessage)) {
      cancelPendingIfExists(data, tenantId);
      data.professionalId = null;
      data.professionalName = null;
      data.date = null;
      data.preferredPeriod = null;
      data.time = null;
      data.appointmentId = null;
      data.sourceAppointmentId = null;
      data.professionalOptionIds.clear();
      data.professionalOptionNames.clear();
      data.availableTimeOptions.clear();
      data.stage = ConversationStage.ASK_PROFESSIONAL;
      return data.serviceId == null
          ? "Primeiro me fala o serviço, tá?"
          : "Tudo bem! " + buildProfessionalPrompt(data, tenantId);
    }

    if (isBackCommand(normalizedMessage)) {
      return goBackOneStep(data, tenantId);
    }

    return null;
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

  private ConversationStateEntity createState(UUID tenantId, String userIdentifier) {
    ConversationStateEntity entity = new ConversationStateEntity();
    entity.tenantId = tenantId;
    entity.userIdentifier = userIdentifier;
    entity.stateJson = toJson(new ConversationData());
    entity.updatedAt = Instant.now();
    return entity;
  }

  private ConversationData parseState(String stateJson) {
    if (stateJson == null || stateJson.isBlank()) return new ConversationData();
    try {
      return objectMapper.readValue(stateJson, ConversationData.class);
    } catch (Exception e) {
      LOG.warnf("Estado de conversa corrompido, reiniciando sessao. Erro: %s | JSON: %.200s",
          e.getMessage(), stateJson);
      return new ConversationData();
    }
  }

  private String toJson(ConversationData data) {
    try {
      return objectMapper.writeValueAsString(data);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Falha ao serializar estado da conversa", e);
    }
  }

  private String normalizeTime(String value) {
    return value.length() == 5 ? value : value.substring(0, 5);
  }

  /**
   * Acrescenta sugestão de atendente humano ao final da mensagem quando o usuário
   * fica preso num mesmo estágio por 3 ou mais tentativas consecutivas.
   */
  private String withHandoffIfNeeded(ConversationData data, String base) {
    if (data.stageAttempts >= 3) {
      markManualIntervention(data, "STAGE_RETRY_LIMIT");
      String suggestion = ollamaResponseService.generateHandoffSuggestion()
          .orElse("Estou com dificuldade de entender 😅 Quer falar com uma atendente? Responde SIM! 👩");
      return base + "\n\n" + suggestion;
    }
    return base;
  }

  private void clearManualInterventionSignal(ConversationData data) {
    if (data == null) return;
    data.manualInterventionSuggested = false;
    data.manualInterventionReason = null;
    data.manualInterventionAttempts = null;
  }

  private void markManualIntervention(ConversationData data, String reason) {
    if (data == null) return;
    data.manualInterventionSuggested = true;
    data.manualInterventionReason = reason;
    data.manualInterventionAttempts = data.stageAttempts;
  }

  private String buildProfessionalPrompt(ConversationData data, String tenantId) {
    List<ProfissionalDto> options = domainService.listProfessionalsByService(tenantId, data.serviceId);
    if (options.isEmpty()) {
      return "Hmm, não encontrei profissionais disponíveis pra esse serviço. 😕 Tenta outro?";
    }

    data.professionalOptionIds.clear();
    data.professionalOptionNames.clear();
    data.professionalOptionSpecialtyNames.clear();
    int limit = Math.min(options.size(), 10);
    for (int i = 0; i < limit; i++) {
      ProfissionalDto p = options.get(i);
      data.professionalOptionIds.add(p.id);
      data.professionalOptionNames.add(p.name);
      String specName = domainService.firstSpecialtyName(p);
      data.professionalOptionSpecialtyNames.add(specName != null ? specName : "");
    }

    // Tenta resposta humanizada via LLM
    java.util.Optional<String> llmReply = ollamaResponseService.generateProfessionalsMessage(
        data.customerName, data.serviceName, options.subList(0, limit));
    if (llmReply.isPresent()) {
      return llmReply.get();
    }
    return "Com quem você quer? Escolha pelo número ou nome:\n" + buildNumberedProfessionalList(options, limit);
  }

  private String buildNumberedProfessionalList(List<ProfissionalDto> options, int limit) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < limit; i++) {
      if (i > 0) out.append("\n");
      ProfissionalDto p = options.get(i);
      String specName = domainService.firstSpecialtyName(p);
      String specDesc = (p.specialtiesDetailed != null && !p.specialtiesDetailed.isEmpty()
          && p.specialtiesDetailed.get(0).description != null
          && !p.specialtiesDetailed.get(0).description.isBlank())
          ? p.specialtiesDetailed.get(0).description : null;
      out.append(i + 1).append(" - ").append(p.name);
      if (specName != null) {
        out.append(" (").append(specName).append(")");
        if (specDesc != null) {
          out.append("\n   ").append(specDesc);
        }
      }
    }
    out.append("\nPode mandar o número ou o nome. 😊");
    return out.toString();
  }

  private String buildNumberedTimeList(List<String> times) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < times.size(); i++) {
      if (i > 0) out.append("\n");
      out.append(i + 1).append(" - ").append(times.get(i));
    }
    out.append("\nPode mandar o número ou o horário. ⏰");
    return out.toString();
  }

  private String buildNumberedAppointmentList(List<String> labels) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < labels.size(); i++) {
      if (i > 0) out.append("\n");
      out.append(i + 1).append(" - ").append(labels.get(i));
    }
    out.append("\nManda o número do agendamento. 😊");
    return out.toString();
  }

  private OptionalInt parseOrdinalSelection(String rawMessage, int optionSize) {
    if (optionSize <= 0) return OptionalInt.empty();
    String normalized = TextNormalizer.normalize(rawMessage);
    if ("ultimo".equals(normalized) || "ultima".equals(normalized) || "ult".equals(normalized)) {
      return OptionalInt.of(optionSize - 1);
    }
    if (normalized.matches("^\\d+$")) {
      int index = Integer.parseInt(normalized) - 1;
      if (index >= 0 && index < optionSize) {
        return OptionalInt.of(index);
      }
    }
    return OptionalInt.empty();
  }

  private UUID parseSelectedAppointmentId(ConversationData data, String rawMessage) {
    if (data.appointmentOptionIds.isEmpty()) return null;
    OptionalInt index = parseOrdinalSelection(rawMessage, data.appointmentOptionIds.size());
    if (index.isEmpty()) return null;
    return UUID.fromString(data.appointmentOptionIds.get(index.getAsInt()));
  }

  private String extractCustomerName(String rawMessage) {
    if (rawMessage == null) return null;
    String trimmed = rawMessage.trim();
    if (trimmed.length() < 2 || trimmed.length() > 80) return null;

    // Rejeita strings com 3 ou mais dígitos (telefones, datas, códigos)
    // mas permite nomes como "João 2º" ou "Ana 3ª"
    long digitCount = trimmed.chars().filter(Character::isDigit).count();
    if (digitCount > 2) return null;

    String normalized = TextNormalizer.normalize(trimmed);
    if (normalized.isBlank()) return null;
    if (DateTimeRegexExtractor.isAffirmative(normalized) || DateTimeRegexExtractor.isNegative(normalized)) return null;
    if (normalized.matches("^\\d+$")) return null;
    if (mentionsBookingIntent(normalized)) return null;
    if (DateTimeRegexExtractor.extractDate(normalized).isPresent()) return null;
    if (DateTimeRegexExtractor.extractTime(normalized).isPresent()) return null;

    return trimmed;
  }

  private boolean isRestartCommand(String normalized) {
    return normalized.equals("reiniciar")
        || normalized.equals("recomecar")
        || normalized.equals("comecar de novo")
        || normalized.equals("novo agendamento")
        || normalized.equals("fazer novo agendamento")
        || normalized.equals("cancelar pedido")
        || normalized.equals("cancelar tudo");
  }

  private boolean isChangeServiceCommand(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    if (!hasWord(normalized, "servico")) return false;
    return hasWord(normalized, "trocar")
        || hasWord(normalized, "mudar")
        || hasWord(normalized, "alterar")
        || hasWord(normalized, "outro")
        || hasWord(normalized, "voltar");
  }

  private boolean isChangeProfessionalCommand(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    if (!hasWord(normalized, "profissional")) return false;
    return hasWord(normalized, "trocar")
        || hasWord(normalized, "mudar")
        || hasWord(normalized, "alterar")
        || hasWord(normalized, "outro")
        || hasWord(normalized, "voltar");
  }

  private boolean isBackCommand(String normalized) {
    return normalized.equals("voltar")
        || normalized.equals("volta")
        || normalized.equals("etapa anterior")
        || normalized.equals("passo anterior");
  }

  private void cancelPendingIfExists(ConversationData data, String tenantId) {
    if (data.appointmentId == null) return;
    try {
      domainService.cancelAppointment(tenantId, data.appointmentId, data.userIdentifier);
    } catch (RuntimeException ignored) {
    }
    data.appointmentId = null;
  }

  private String iniciarFluxoCancelamento(ConversationData data, String userIdentifier, String tenantId) {
    clearBookingFlow(data, tenantId);
    List<AssistantDomainService.UpcomingAppointmentOption> options =
        domainService.listUpcomingOptionsForUser(tenantId, userIdentifier, 10);
    if (options.isEmpty()) {
      data.stage = ConversationStage.START;
      return "Não encontrei agendamentos ativos pra cancelar. 😊";
    }

    fillAppointmentOptions(data, options);
    data.stage = ConversationStage.ASK_CANCEL_APPOINTMENT;
    return "Qual você quer cancelar? Manda o número:\n" + buildNumberedAppointmentList(data.appointmentOptionLabels);
  }

  private String iniciarFluxoRemarcacao(ConversationData data, String userIdentifier, String tenantId) {
    clearBookingFlow(data, tenantId);
    List<AssistantDomainService.UpcomingAppointmentOption> options =
        domainService.listUpcomingOptionsForUser(tenantId, userIdentifier, 10);
    if (options.isEmpty()) {
      data.stage = ConversationStage.START;
      return "Não encontrei agendamentos ativos pra remarcar. 😊";
    }

    fillAppointmentOptions(data, options);
    data.stage = ConversationStage.ASK_RESCHEDULE_APPOINTMENT;
    return "Qual você quer remarcar? Manda o número:\n" + buildNumberedAppointmentList(data.appointmentOptionLabels);
  }

  private void fillAppointmentOptions(ConversationData data, List<AssistantDomainService.UpcomingAppointmentOption> options) {
    data.appointmentOptionIds.clear();
    data.appointmentOptionLabels.clear();
    for (AssistantDomainService.UpcomingAppointmentOption item : options) {
      data.appointmentOptionIds.add(item.appointmentId.toString());
      data.appointmentOptionLabels.add(
          item.serviceName + " com " + item.professionalName + " em " + item.date + " às " + item.time);
    }
  }

  private String saudacaoPorHorario() {
    LocalTime agora = LocalTime.now(ZoneId.of(greetingZone));
    if (agora.isBefore(LocalTime.NOON)) return "Bom dia";
    if (agora.isBefore(LocalTime.of(18, 0))) return "Boa tarde";
    return "Boa noite";
  }

  private String greetingReplyForCurrentStage(ConversationData data, String tenantId) {
    String saudacao = saudacaoPorHorario();
    String nome = (data.customerName != null && !data.customerName.isBlank()) ? ", " + data.customerName : "";
    String greetingBase = saudacao + nome + ".";

    if (data.stage == ConversationStage.START || data.stage == ConversationStage.COMPLETED) {
      if (data.customerName != null && !data.customerName.isBlank()) {
        data.stage = ConversationStage.ASK_SERVICE;
        return greetingBase + " Que bom te ver de novo! 😊 " + domainService.formatServicesPrompt(tenantId);
      }
      data.stage = ConversationStage.ASK_NAME;
      return greetingBase + " Que bom falar com você! Pra começar, me conta seu nome. 😊";
    }
    if (data.stage == ConversationStage.ASK_NAME) {
      return greetingBase + " Pra continuar, me conta seu nome completo. 😊";
    }
    if (data.stage == ConversationStage.ASK_SERVICE) {
      return greetingBase + " Vamos lá! " + domainService.formatServicesPrompt(tenantId);
    }
    if (data.stage == ConversationStage.ASK_PROFESSIONAL) {
      return greetingBase + " Com quem você quer? Me fala o profissional. 😊";
    }
    if (data.stage == ConversationStage.ASK_DATE) {
      return greetingBase + " Que dia fica bom? 📅";
    }
    if (data.stage == ConversationStage.ASK_PERIOD) {
      return greetingBase + " Prefere de manhã, tarde ou noite? ☀️🌙";
    }
    if (data.stage == ConversationStage.ASK_TIME) {
      String periodLabel = data.preferredPeriod != null ? data.preferredPeriod.label() : "seu período";
      return greetingBase + " Me fala o horário de " + periodLabel + " que prefere. ⏰";
    }
    if (data.stage == ConversationStage.ASK_CANCEL_APPOINTMENT) {
      return greetingBase + " Qual você quer cancelar? Manda o número. 😊";
    }
    if (data.stage == ConversationStage.ASK_RESCHEDULE_APPOINTMENT) {
      return greetingBase + " Qual você quer remarcar? Manda o número. 😊";
    }
    return greetingBase + " Vamos continuar! Pode mandar. 😊";
  }

  private boolean isDeterministicGreeting(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    return normalized.equals("oi")
        || normalized.equals("ola")
        || normalized.equals("olaa")
        || normalized.equals("oii")
        || normalized.equals("oiii")
        || normalized.equals("opa")
        || normalized.equals("hey")
        || normalized.equals("salve")
        || normalized.equals("bom dia")
        || normalized.equals("boa tarde")
        || normalized.equals("boa noite")
        || normalized.equals("ola tudo bem")
        || normalized.equals("bom dia tudo bem")
        || normalized.equals("boa tarde tudo certo")
        || normalized.equals("boa noite tudo bem");
  }

  private boolean wantsChangeDay(String normalized) {
    return normalized.equals("trocar dia")
        || normalized.equals("mudar dia")
        || normalized.equals("outro dia")
        || normalized.equals("alterar dia");
  }

  private boolean isPriceQuery(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    return hasWord(normalized, "valor")
        || hasWord(normalized, "preco")
        || normalized.contains("quanto custa")
        || normalized.contains("quanto cobram")
        || normalized.contains("quanto fica")
        || normalized.contains("qual o preco")
        || normalized.contains("qual o valor")
        || normalized.contains("quais os precos")
        || normalized.contains("quais os valores")
        || normalized.contains("tabela de preco")
        || normalized.contains("lista de preco")
        || normalized.contains("ver preco")
        || normalized.contains("ver valor")
        || (hasWord(normalized, "custa") && normalized.length() < 60);
  }

  private boolean shouldPrioritizeSlotInput(ConversationData data, String rawMessage, String normalized) {
    if (wantsChangeDay(normalized)) {
      return data.serviceId != null && data.professionalId != null;
    }

    boolean waitingDate = data.stage == ConversationStage.ASK_DATE
        || (data.serviceId != null && data.professionalId != null && data.date == null);
    if (waitingDate && DateTimeRegexExtractor.extractDate(rawMessage).isPresent()) {
      return true;
    }

    boolean waitingPeriod = data.stage == ConversationStage.ASK_PERIOD && TimePeriod.fromText(normalized).isPresent();
    if (waitingPeriod) return true;

    boolean waitingTime = data.stage == ConversationStage.ASK_TIME
        && (DateTimeRegexExtractor.extractTime(rawMessage).isPresent() || TimePeriod.fromText(normalized).isPresent());
    return waitingTime;
  }

  private String goBackOneStep(ConversationData data, String tenantId) {
    cancelPendingIfExists(data, tenantId);
    data.sourceAppointmentId = null;
    data.appointmentOptionIds.clear();
    data.appointmentOptionLabels.clear();
    data.availableTimeOptions.clear();

    if (data.stage == ConversationStage.CONFIRMATION || data.stage == ConversationStage.ASK_TIME) {
      data.time = null;
      data.stage = ConversationStage.ASK_PERIOD;
      return "Voltei! Prefere de manhã, tarde ou noite? ☀️🌙";
    }
    if (data.stage == ConversationStage.ASK_PERIOD) {
      data.preferredPeriod = null;
      data.time = null;
      data.stage = ConversationStage.ASK_DATE;
      return "Voltei! Que dia você quer? 📅";
    }
    if (data.stage == ConversationStage.ASK_DATE) {
      data.date = null;
      data.preferredPeriod = null;
      data.time = null;
      data.professionalId = null;
      data.professionalName = null;
      data.stage = ConversationStage.ASK_PROFESSIONAL;
      return data.serviceId == null
          ? "Voltei! Qual serviço você quer?"
          : "Voltei! " + buildProfessionalPrompt(data, tenantId);
    }
    if (data.stage == ConversationStage.ASK_PROFESSIONAL) {
      data.professionalId = null;
      data.professionalName = null;
      data.date = null;
      data.preferredPeriod = null;
      data.time = null;
      data.stage = ConversationStage.ASK_SERVICE;
      return "Voltei! Qual serviço você quer? 💅";
    }
    if (data.stage == ConversationStage.ASK_SERVICE) {
      data.serviceId = null;
      data.serviceName = null;
      data.stage = ConversationStage.ASK_NAME;
      return "Voltei! Me conta seu nome completo. 😊";
    }
    if (data.stage == ConversationStage.ASK_NAME) {
      return "Você já está na primeira etapa. Me conta seu nome pra continuar. 😊";
    }

    data.stage = ConversationStage.ASK_SERVICE;
    return "Vamos lá! " + domainService.formatServicesPrompt(tenantId);
  }

  private void resetFlowKeepCustomer(ConversationData data) {
    String customerName = data.customerName;
    data.reset();
    data.customerName = customerName;
  }

  private void clearBookingFlow(ConversationData data, String tenantId) {
    cancelPendingIfExists(data, tenantId);
    data.serviceId = null;
    data.serviceName = null;
    data.professionalId = null;
    data.professionalName = null;
    data.date = null;
    data.time = null;
    data.preferredPeriod = null;
    data.appointmentId = null;
    data.sourceAppointmentId = null;
    data.professionalOptionIds.clear();
    data.professionalOptionNames.clear();
    data.availableTimeOptions.clear();
    data.appointmentOptionIds.clear();
    data.appointmentOptionLabels.clear();
  }

  // ─── Enriquecimento de datas ──────────────────────────────────────────────────

  /**
   * Substitui expressões de data relativas por datas ISO explícitas antes de enviar ao LLM.
   *
   * <p>Modelos menores (8B) são inconsistentes ao calcular "amanhã" a partir da data
   * do system prompt. Resolver em Java garante 100% de precisão.</p>
   *
   * <p>Exemplos:</p>
   * <ul>
   *   <li>"amanhã às 10h"         → "2026-03-28 (amanhã) às 10h"</li>
   *   <li>"depois de amanhã"      → "2026-03-29 (depois de amanhã)"</li>
   *   <li>"hoje às 14h"           → "2026-03-27 (hoje) às 14h"</li>
   *   <li>"na sexta"              → "na sexta (2026-03-28)"</li>
   *   <li>"semana que vem"        → "semana que vem (a partir de 2026-04-03)"</li>
   * </ul>
   */
  private String enrichDatesInMessage(String message) {
    if (message == null || message.isBlank()) return message;

    LocalDate today        = LocalDate.now();
    LocalDate tomorrow     = today.plusDays(1);
    LocalDate afterTomorrow = today.plusDays(2);

    // Ordem importa: "depois de amanhã" antes de "amanhã"
    String result = message
        .replaceAll("(?i)\\bdepois\\s+de\\s+amanhã\\b",
            "depois de amanhã (" + afterTomorrow + ")")
        .replaceAll("(?i)\\bamanhã\\b",
            "amanhã (" + tomorrow + ")")
        .replaceAll("(?i)\\bhoje\\b",
            "hoje (" + today + ")")
        .replaceAll("(?i)\\bessa\\s+semana\\b",
            "essa semana (de " + today + " a " + today.with(DayOfWeek.SUNDAY) + ")")
        .replaceAll("(?i)\\bsemana\\s+que\\s+vem\\b",
            "semana que vem (a partir de " + today.plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) + ")");

    // Dias da semana → próxima ocorrência futura
    result = resolveDayOfWeek(result, "segunda(?:-feira)?", DayOfWeek.MONDAY,  today);
    result = resolveDayOfWeek(result, "terça(?:-feira)?",   DayOfWeek.TUESDAY,  today);
    result = resolveDayOfWeek(result, "quarta(?:-feira)?",  DayOfWeek.WEDNESDAY, today);
    result = resolveDayOfWeek(result, "quinta(?:-feira)?",  DayOfWeek.THURSDAY, today);
    result = resolveDayOfWeek(result, "sexta(?:-feira)?",   DayOfWeek.FRIDAY,   today);
    result = resolveDayOfWeek(result, "sábado|sabado",      DayOfWeek.SATURDAY, today);
    result = resolveDayOfWeek(result, "domingo",            DayOfWeek.SUNDAY,   today);

    if (!result.equals(message)) {
      LOG.debugf("[Agent] Datas enriquecidas: '%s' → '%s'", message, result);
    }
    return result;
  }

  /** Substitui o nome do dia por "nome (YYYY-MM-DD)" usando a próxima ocorrência futura. */
  private String resolveDayOfWeek(String text, String dayPattern, DayOfWeek dow, LocalDate today) {
    // Próxima ocorrência: se hoje é sexta e usuário diz "sexta", assume próxima sexta (não hoje)
    LocalDate next = today.plusDays(1).with(TemporalAdjusters.nextOrSame(dow));
    Pattern p = Pattern.compile("(?i)(?:na\\s+|no\\s+|na\\s+próxima\\s+|no\\s+próximo\\s+)?(" + dayPattern + ")", Pattern.UNICODE_CASE);
    Matcher m = p.matcher(text);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      m.appendReplacement(sb, m.group(0).trim() + " (" + next + ")");
    }
    m.appendTail(sb);
    return sb.toString();
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

  private IntentPrediction enrichIntentWithOllama(IntentPrediction current, String rawMessage, ConversationStage stage) {
    // GREETING é sempre rápido — não precisa do Ollama
    if (current.intent == IntentType.GREETING) {
      LOG.debugf("[Ollama] Pulado: saudação detectada pelo OpenNLP");
      return current;
    }
    if (current.intent != IntentType.UNKNOWN && current.confidence >= minIntentConfidence) {
      LOG.debugf("[Ollama] Pulado: OpenNLP confiante (intent=%s conf=%.2f)", current.intent, current.confidence);
      return current;
    }
    // Stages onde o usuário preenche slot literal ou a etapa tem seu próprio handler —
    // intent enrichment não agrega valor e pode atrasar a resposta (Ollama ~10-15s no CPU)
    if (stage == ConversationStage.ASK_DATE
        || stage == ConversationStage.ASK_PERIOD
        || stage == ConversationStage.ASK_TIME
        || stage == ConversationStage.ASK_NAME
        || stage == ConversationStage.CONFIRMATION) {
      LOG.debugf("[Ollama] Pulado: stage=%s aguarda slot literal ou tem handler próprio", stage);
      return current;
    }

    LOG.infof("[Ollama] OpenNLP inseguro (intent=%s conf=%.2f stage=%s) → tentando Ollama", current.intent, current.confidence, stage);
    return ollamaIntentService.classify(rawMessage, stage.name())
        .filter(p -> p.confidence >= ollamaMinConfidence && p.intent != IntentType.UNKNOWN)
        .orElseGet(() -> {
          LOG.infof("[Ollama] Sem resultado útil → mantendo OpenNLP (intent=%s)", current.intent);
          return current;
        });
  }

  private String handleLowConfidenceIntent(ConversationData data, IntentPrediction intentPrediction, boolean prioritizeSlotInput) {
    if (prioritizeSlotInput) return null;
    if (intentPrediction.intent == IntentType.GREETING) return null;
    if (intentPrediction.intent != IntentType.UNKNOWN && intentPrediction.confidence >= minIntentConfidence) return null;

    // Stages onde o usuário preenche slot literal: deixar a lógica de resolução tentar.
    // Se falhar, cada stage já tem sua própria mensagem de reprompt (serviços, profissionais, etc.)
    if (data.stage == ConversationStage.ASK_DATE
        || data.stage == ConversationStage.ASK_PERIOD
        || data.stage == ConversationStage.ASK_TIME
        || data.stage == ConversationStage.ASK_NAME
        || data.stage == ConversationStage.ASK_SERVICE
        || data.stage == ConversationStage.ASK_PROFESSIONAL
        || data.stage == ConversationStage.CONFIRMATION) return null;

    // START / COMPLETED / cancel / reschedule stages: menu global faz sentido
    return "Não entendi direito. O que você quer? 😊\n1 - Agendar\n2 - Remarcar\n3 - Cancelar\n4 - Ver meus agendamentos";
  }

  /**
   * Verifica se a mensagem parece uma expressão temporal (data ou referência de dia).
   * Usada como guarda antes de chamar o Ollama date enricher para evitar alucinações
   * com inputs não relacionados a datas (ex: nomes de serviço, seleções ordinais, etc.).
   *
   * Regras:
   *  - Rejeita seleções ordinais simples ("1", "2", "ultimo") → não são datas
   *  - Aceita padrões numéricos de data (dois ou mais dígitos, separadores /, -)
   *  - Aceita palavras-chave temporais PT-BR ("sexta", "semana que vem", etc.)
   */
  private boolean looksLikeTemporalExpression(String msg) {
    if (msg == null || msg.isBlank()) return false;
    String n = TextNormalizer.normalize(msg).trim();

    // Rejeição explícita: seleção ordinal pura (1-20) ou "ultimo" → não é data
    if (n.matches("^\\d{1,2}$")) return false;
    if (n.equals("ultimo") || n.equals("ultima") || n.equals("ult")) return false;

    // Padrão numérico de data: dois ou mais dígitos COM separador (10/03, 2026-03, etc.)
    if (n.matches(".*\\d{1,2}[/\\-.]\\d.*")) return true;

    // Quatro dígitos consecutivos → provavelmente ano (ex: "2026")
    if (n.matches(".*\\d{4}.*")) return true;

    // Dois ou mais dígitos SOZINHOS sem separador → pode ser dia (ex: "25")
    if (n.matches("^\\d{2,}$")) return true;

    // Palavras-chave temporais PT-BR
    String[] temporalKeywords = {
        "amanha", "hoje", "ontem",
        "semana", "mes", "ano", "proxim", "ultim",
        "segunda", "terca", "quarta", "quinta", "sexta", "sabado", "domingo",
        "janeiro", "fevereiro", "marco", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
        "inicio", "fim", "comeco", "final", "agora", "logo"
    };
    for (String kw : temporalKeywords) {
      if (n.contains(kw)) return true;
    }
    return false;
  }

  /**
   * Confirmações informais comuns no WhatsApp — tratadas deterministicamente para evitar
   * latência do Ollama (~10-15 s no CPU) em casos simples.
   */
  private boolean isInformalAffirmative(String normalized) {
    if (normalized == null) return false;
    // Prefixo "sim" cobre "sim sim", "sim pode ser", "sim quero"
    if (normalized.startsWith("sim")) return true;
    // Prefixo "pode" cobre "pode", "pode ser", "pode confirmar"
    if (normalized.startsWith("pode")) return true;
    return switch (normalized) {
      case "fecha", "fecha ai", "fecha aí",
           "claro", "claro que sim",
           "ok", "okay",
           "bora", "vamo", "vamos",
           "perfeito",
           "confirmado", "confirma", "confirmar",
           "ta", "ta bom", "ta certo", "tá", "tá bom", "tá certo",
           "isso", "isso mesmo",
           "vai", "vai sim",
           "combinado" -> true;
      default -> false;
    };
  }

  /**
   * Negações informais comuns no WhatsApp — tratadas deterministicamente para evitar
   * latência do Ollama (~10-15 s no CPU) em casos simples.
   */
  private boolean isInformalNegative(String normalized) {
    if (normalized == null) return false;
    // Prefixo "nao" cobre "nao quero", "nao obrigado", "nao preciso"
    if (normalized.startsWith("nao ")) return true;
    return switch (normalized) {
      case "nao", "n",
           "nope",
           "desisto", "desistir",
           "deixa pra la", "deixa pra lá", "deixa",
           "cancela", "cancela ai", "cancela aí",
           "esquece", "esquece isso",
           "nao quero", "nao obrigado" -> true;
      default -> false;
    };
  }

  /**
   * Lida com a resposta do cliente ao lembrete automático de agendamento.
   * O contexto foi pré-semeado pelo ReminderScheduler com stage=AWAITING_APPOINTMENT_CONFIRMATION.
   * Processa deterministicamente: sem LLM, sem novo fluxo de booking.
   */
  private String handleReminderConfirmation(ConversationData data, String normalized,
      String userIdentifier, String tenantId) {
    if (data.appointmentId == null) {
      // Estado corrompido — reinicia sem travar o usuário
      data.reset();
      return "Oi! Como posso te ajudar hoje? 😊";
    }

    String nome = (data.customerName != null && !data.customerName.isBlank())
        ? ", " + data.customerName : "";

    if (isInformalAffirmative(normalized) || DateTimeRegexExtractor.isAffirmative(normalized)
        || normalized.contains("confirmar") || normalized.contains("confirmo")) {
      try {
        domainService.confirmAppointment(tenantId, data.appointmentId, userIdentifier);
        data.reset();
        LOG.infof("[Reminder] Presença confirmada: appointment=%s user=%s", data.appointmentId, userIdentifier);
        return "Presença confirmada" + nome + "! ✅ Te esperamos. Se precisar de algo, é só chamar. 😊";
      } catch (Exception e) {
        LOG.warnf("[Reminder] Erro ao confirmar presença: %s", e.getMessage());
        data.reset();
        return "Tive um problema ao confirmar. Por favor, entre em contato com o salão. 😕";
      }
    }

    if (isInformalNegative(normalized) || DateTimeRegexExtractor.isNegative(normalized)
        || normalized.contains("cancelar") || normalized.contains("cancelo")) {
      try {
        domainService.cancelAppointmentForUser(tenantId, userIdentifier, data.appointmentId);
        data.reset();
        LOG.infof("[Reminder] Agendamento cancelado via lembrete: appointment=%s user=%s", data.appointmentId, userIdentifier);
        return "Agendamento cancelado" + nome + ". 😊 Quando quiser remarcar, é só chamar!";
      } catch (Exception e) {
        LOG.warnf("[Reminder] Erro ao cancelar via lembrete: %s", e.getMessage());
        data.reset();
        return "Tive um problema ao cancelar. Por favor, entre em contato com o salão. 😕";
      }
    }

    return "Não entendi" + nome + ". Responde *CONFIRMAR* pra confirmar sua presença ou *CANCELAR* pra cancelar o agendamento. 😊";
  }

  private String handleReactivationReply(
      ConversationData data,
      String rawMessage,
      String normalized,
      String userIdentifier,
      String tenantId) {
    String nome = (data.customerName != null && !data.customerName.isBlank())
        ? ", " + data.customerName : "";

    if (isInformalNegative(normalized) || DateTimeRegexExtractor.isNegative(normalized)
        || normalized.contains("nao quero") || normalized.contains("deixa pra la")) {
      data.reset();
      return "Tudo certo" + nome + ". Se quiser agendar depois, e so me chamar por aqui. ??";
    }

    ConversationStage resumeStage = data.reactivationResumeStage != null
        ? data.reactivationResumeStage
        : ConversationStage.ASK_SERVICE;
    data.stage = resumeStage;
    data.reactivationResumeStage = null;

    if (isInformalAffirmative(normalized) || DateTimeRegexExtractor.isAffirmative(normalized)
        || normalized.contains("quero") || normalized.contains("vamos")) {
      return "Perfeito" + nome + "! Vamos retomar de onde paramos. " + greetingReplyForCurrentStage(data, tenantId);
    }

    return handleMessage(data, rawMessage, userIdentifier, tenantId);
  }

  /**
   * Verifica se a última mensagem do assistente no histórico foi um pedido de confirmação
   * de agendamento. Usado pelo safety net do modo agente para detectar quando o LLM
   * esqueceu de emitir [CRIAR_AGENDAMENTO] após o cliente confirmar.
   */
  private boolean isConfirmationPending(List<ChatMessage> history) {
    if (history == null || history.isEmpty()) return false;
    for (int i = history.size() - 1; i >= 0; i--) {
      ChatMessage msg = history.get(i);
      if (!"assistant".equals(msg.role)) continue;
      String c = TextNormalizer.normalize(msg.content);
      return c.contains("confirma") || c.contains("pode confirmar")
          || c.contains("confirmar o agendamento") || c.contains("confirma o agendamento")
          || c.contains("posso confirmar") || c.contains("confirmar?");
    }
    return false;
  }

  /**
   * Verifica se a mensagem do usuário é uma resposta afirmativa — reutiliza os mesmos
   * padrões deterministicos do fluxo de máquina de estados.
   */
  private boolean isAffirmativeResponse(String rawMessage) {
    if (rawMessage == null || rawMessage.isBlank()) return false;
    String normalized = TextNormalizer.normalize(rawMessage);
    return isInformalAffirmative(normalized) || DateTimeRegexExtractor.isAffirmative(normalized);
  }

  String compactMessageForLlm(String message) {
    if (message == null || message.isBlank()) return message;

    String normalized = message
        .replace("\r\n", "\n")
        .replaceAll("[ \\t]{2,}", " ")
        .replaceAll("\\n{3,}", "\n\n")
        .trim();

    int maxChars = Math.max(llmMaxInputChars, 200);
    if (normalized.length() <= maxChars) {
      return normalized;
    }

    String notice = "\n[Mensagem longa truncada pelo sistema. Foque no pedido principal visivel.]\n";
    int remaining = maxChars - notice.length();
    if (remaining <= 120) {
      return normalized.substring(0, Math.min(normalized.length(), maxChars));
    }

    int head = Math.max(remaining * 2 / 3, 120);
    int tail = Math.max(remaining - head, 80);
    head = Math.min(head, normalized.length());
    tail = Math.min(tail, normalized.length() - head);

    String compacted = normalized.substring(0, head).trim()
        + notice
        + normalized.substring(normalized.length() - tail).trim();
    LOG.infof("[Agent] Mensagem compactada para o LLM: original=%d chars compactada=%d chars",
        normalized.length(),
        compacted.length());
    return compacted;
  }

  LlmBookingAgent.AgentChatOptions buildAgentChatOptions(ConversationData data, String rawMessage) {
    if (!shouldUseShortReplyMode(data, rawMessage)) {
      return LlmBookingAgent.AgentChatOptions.defaultOptions();
    }

    return new LlmBookingAgent.AgentChatOptions(
        Math.max(shortResponseMaxTokens, 24),
        """
        RESPOSTA CURTA:
        - Se a pergunta for simples, responda em no maximo 1 frase curta.
        - Seja direta e objetiva.
        - Nao use listas nem explicacoes longas.
        - Nao emita action tokens desnecessarios.
        """);
  }

  boolean shouldUseShortReplyMode(ConversationData data, String rawMessage) {
    if (rawMessage == null || rawMessage.isBlank()) return false;
    if (data == null) return false;
    if (data.stage != ConversationStage.START && data.stage != ConversationStage.COMPLETED) {
      return false;
    }

    try {
      IntentPrediction prediction = intentClassifier.classifyWithConfidence(rawMessage);
      if (prediction == null) return false;
      return prediction.intent == IntentType.GREETING || prediction.intent == IntentType.UNKNOWN;
    } catch (Exception e) {
      LOG.debugf("[Agent] Falha ao classificar pergunta padrao para resposta curta: %s", e.getMessage());
      return false;
    }
  }

  private BookingLeadSignals detectBookingLeadSignals(String rawMessage, ConversationData data, String tenantId) {
    BookingLeadSignals signals = new BookingLeadSignals();
    if (rawMessage == null || rawMessage.isBlank()) return signals;

    String normalized = TextNormalizer.normalize(rawMessage);
    boolean bookingIntent = mentionsBookingIntent(normalized) || isBookIntent(rawMessage);

    if (data.serviceId != null || (data.serviceName != null && !data.serviceName.isBlank())) {
      signals.serviceId = data.serviceId != null ? data.serviceId.toString() : null;
      signals.serviceName = data.serviceName;
    } else {
      Optional<ServicoDto> resolvedService;
      Optional<String> extractedName = serviceNameFinder.extractFirst(rawMessage);
      resolvedService = extractedName.flatMap(name -> domainService.resolveService(tenantId, name));
      if (resolvedService.isEmpty()) {
        resolvedService = domainService.resolveService(tenantId, rawMessage);
      }
      if (resolvedService.isPresent()) {
        signals.serviceId = resolvedService.get().id;
        signals.serviceName = resolvedService.get().name;
      }
    }

    LocalDate resolvedDate = data.date != null ? data.date : DateTimeRegexExtractor.extractDate(rawMessage).orElse(null);
    String resolvedTime = data.time != null && !data.time.isBlank()
        ? data.time
        : DateTimeRegexExtractor.extractTime(rawMessage).map(this::normalizeTime).orElse(null);

    signals.date = resolvedDate != null ? resolvedDate.toString() : null;
    signals.time = resolvedTime;
    signals.detected = bookingIntent && (signals.serviceId != null || signals.serviceName != null || signals.date != null || signals.time != null);
    return signals;
  }

  private boolean mentionsBookingIntent(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    return normalized.contains("agendar")
        || normalized.contains("marcar")
        || normalized.contains("horario")
        || normalized.contains("agenda")
        || normalized.contains("quero atendimento")
        || normalized.contains("quero um horario");
  }

  private boolean isBookIntent(String rawMessage) {
    try {
      IntentPrediction prediction = intentClassifier.classifyWithConfidence(rawMessage);
      return prediction != null
          && prediction.intent == IntentType.BOOK
          && prediction.confidence >= minIntentConfidence;
    } catch (Exception ignored) {
      return false;
    }
  }

  private static final class BookingLeadSignals {
    private boolean detected;
    private String serviceId;
    private String serviceName;
    private String date;
    private String time;
  }

  private boolean hasWord(String text, String word) {
    if (text == null || word == null || word.isBlank()) return false;
    String[] parts = text.split("\\s+");
    for (String part : parts) {
      if (word.equals(part)) return true;
    }
    return false;
  }

  private Optional<OllamaSlotExtractor> ollamaSlotExtractor() {
    try {
      if (ollamaSlotExtractorInstance != null && ollamaSlotExtractorInstance.isResolvable()) {
        return Optional.of(ollamaSlotExtractorInstance.get());
      }
    } catch (Exception ignored) {
      // fallback para extratores deterministicos quando bean Ollama nao estiver disponivel.
    }
    return Optional.empty();
  }
}

