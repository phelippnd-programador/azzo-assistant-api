package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
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
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AssistantConversationService {

  private static final Logger LOG = Logger.getLogger(AssistantConversationService.class);

  @Inject OpenNLPIntentClassifier intentClassifier;
  @Inject ServiceNameFinder serviceNameFinder;
  @Inject ProfessionalNameFinder professionalNameFinder;
  @Inject AssistantDomainService domainService;
  @Inject ConversationStateRepository stateRepository;
  @Inject ContextoTenant contextoTenant;
  @Inject ObjectMapper objectMapper;

  @ConfigProperty(name = "assistant.conversation.ttl-minutes", defaultValue = "120")
  long ttlMinutes;
  @ConfigProperty(name = "assistant.greeting-zone", defaultValue = "America/Sao_Paulo")
  String greetingZone;
  @ConfigProperty(name = "assistant.intent.min-confidence", defaultValue = "0.62")
  double minIntentConfidence;

  @Transactional
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

    Instant threshold = Instant.now().minus(Duration.ofMinutes(ttlMinutes));
    stateRepository.deleteExpired(threshold);
    ConversationStateEntity entity = stateRepository
        .findActive(tenantId, userIdentifier, threshold)
        .orElseGet(() -> createState(tenantId, userIdentifier));

    ConversationData data = parseState(entity.stateJson);
    // Mantém userIdentifier sempre atualizado no estado (usado nas notificações)
    data.userIdentifier = userIdentifier;
    if ((data.customerName == null || data.customerName.isBlank()) && userName != null) {
      data.customerName = userName;
    }

    String reply = handleMessage(data, rawMessage, userIdentifier, tenantIdStr);
    if (shouldDeleteConversationState(data)) {
      if (entity.isPersistent()) {
        stateRepository.delete(entity);
      }
    } else {
      entity.stateJson = toJson(data);
      entity.updatedAt = Instant.now();
      stateRepository.persist(entity);
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
    return response;
  }

  private String handleMessage(ConversationData data, String rawMessage, String userIdentifier, String tenantId) {
    String normalized = TextNormalizer.normalize(rawMessage);
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

    String disambiguationReply = handleLowConfidenceIntent(data, intentPrediction, prioritizeSlotInput);
    if (disambiguationReply != null) {
      return disambiguationReply;
    }

    if (data.stage == ConversationStage.CONFIRMATION) {
      if (DateTimeRegexExtractor.isAffirmative(normalized)) {
        domainService.confirmAppointment(tenantId, data.appointmentId, data.userIdentifier);
        if (data.sourceAppointmentId != null && !data.sourceAppointmentId.equals(data.appointmentId)) {
          domainService.cancelAppointmentForUser(tenantId, userIdentifier, data.sourceAppointmentId);
        }
        data.reset();
        return "Agendamento confirmado com sucesso.";
      }
      if (DateTimeRegexExtractor.isNegative(normalized)) {
        domainService.cancelAppointment(tenantId, data.appointmentId, data.userIdentifier);
        data.reset();
        return "Sem problemas. Nao confirmei e limpei este atendimento. "
            + "Quando quiser, iniciamos um novo agendamento.";
      }
      return "Para concluir, responda apenas SIM para confirmar ou NAO para ajustar os dados.";
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
        return "Este salao nao permite cancelamentos pelo WhatsApp no momento.";
      }
      return iniciarFluxoCancelamento(data, userIdentifier, tenantId);
    }
    if (!prioritizeSlotInput && intent == IntentType.RESCHEDULE) {
      if (!domainService.canRescheduleViaWhatsApp(tenantId)) {
        return "Este salao nao permite remarcacoes pelo WhatsApp no momento.";
      }
      return iniciarFluxoRemarcacao(data, userIdentifier, tenantId);
    }

    if (data.stage == ConversationStage.ASK_CANCEL_APPOINTMENT) {
      if (!domainService.canCancelViaWhatsApp(tenantId)) {
        data.stage = ConversationStage.START;
        return "Este salao nao permite cancelamentos pelo WhatsApp no momento.";
      }
      UUID selectedAppointmentId = parseSelectedAppointmentId(data, rawMessage);
      if (selectedAppointmentId == null) {
        return "Escolha qual agendamento cancelar pelo numero: " + buildNumberedAppointmentList(data.appointmentOptionLabels);
      }
      domainService.cancelAppointmentForUser(tenantId, userIdentifier, selectedAppointmentId);
      resetFlowKeepCustomer(data);
      data.stage = ConversationStage.COMPLETED;
      return "Pronto, agendamento cancelado com sucesso. Se quiser, posso iniciar um novo agendamento.";
    }

    if (data.stage == ConversationStage.ASK_RESCHEDULE_APPOINTMENT) {
      if (!domainService.canRescheduleViaWhatsApp(tenantId)) {
        data.stage = ConversationStage.START;
        return "Este salao nao permite remarcacoes pelo WhatsApp no momento.";
      }
      UUID selectedAppointmentId = parseSelectedAppointmentId(data, rawMessage);
      if (selectedAppointmentId == null) {
        return "Escolha qual agendamento voce quer remarcar pelo numero: "
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
      return "Perfeito. Vamos remarcar " + option.serviceName + " com " + option.professionalName
          + ". Qual nova data voce deseja?";
    }

    if (data.stage == ConversationStage.START || data.stage == ConversationStage.COMPLETED) {
      if (!domainService.canScheduleViaWhatsApp(tenantId)) {
        return "Este salao nao permite novos agendamentos pelo WhatsApp no momento.";
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
      } else {
        data.stage = ConversationStage.ASK_NAME;
        return "Antes de agendar, preciso do seu nome. Exemplo: Phelipp Nascimento.";
      }
    }

    if (data.serviceId == null) {
      Optional<String> extracted = serviceNameFinder.extractFirst(rawMessage);
      Optional<ServicoDto> resolved = extracted.flatMap(n -> domainService.resolveService(tenantId, n));
      if (resolved.isEmpty()) {
        resolved = domainService.resolveService(tenantId, rawMessage);
      }
      if (resolved.isPresent()) {
        ServicoDto service = resolved.get();
        data.serviceId = UUID.fromString(service.id);
        data.serviceName = service.name;
        data.professionalOptionIds.clear();
        data.professionalOptionNames.clear();
        data.stage = ConversationStage.ASK_PROFESSIONAL;
      } else {
        data.stage = ConversationStage.ASK_SERVICE;
        return domainService.formatServicesPrompt(tenantId);
      }
    }

    if (data.professionalId == null) {
      if (!data.professionalOptionIds.isEmpty()) {
        OptionalInt index = parseOrdinalSelection(rawMessage, data.professionalOptionIds.size());
        if (index.isPresent()) {
          int selected = index.getAsInt();
          data.professionalId = UUID.fromString(data.professionalOptionIds.get(selected));
          data.professionalName = data.professionalOptionNames.get(selected);
        }
      }

      if (data.professionalId == null) {
        Optional<String> extracted = professionalNameFinder.extractFirst(rawMessage);
        Optional<ProfissionalDto> resolved = extracted.flatMap(n -> domainService.resolveProfessional(tenantId, n, data.serviceId));
        if (resolved.isEmpty()) {
          resolved = domainService.resolveProfessional(tenantId, rawMessage, data.serviceId);
        }
        if (resolved.isPresent()) {
          ProfissionalDto professional = resolved.get();
          data.professionalId = UUID.fromString(professional.id);
          data.professionalName = professional.name;
        }
      }

      if (data.professionalId == null) {
        data.stage = ConversationStage.ASK_PROFESSIONAL;
        return buildProfessionalPrompt(data, tenantId);
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
      if (date.isPresent()) {
        LocalDate extracted = date.get();
        if (extracted.isBefore(java.time.LocalDate.now())) {
          data.stage = ConversationStage.ASK_DATE;
          return "Essa data ja passou. Por favor, informe uma data a partir de hoje. Exemplo: amanha ou 25/04/2026.";
        }
        data.date = extracted;
        data.preferredPeriod = null;
        data.time = null;
        data.availableTimeOptions.clear();
        data.stage = ConversationStage.ASK_PERIOD;
      } else {
        data.stage = ConversationStage.ASK_DATE;
        return "Qual data voce deseja? Exemplo: 25/02/2026 ou amanha.";
      }
    }

    if (data.preferredPeriod == null) {
      Optional<TimePeriod> period = TimePeriod.fromText(normalized);
      if (period.isPresent()) {
        data.preferredPeriod = period.get();
        data.availableTimeOptions.clear();
        data.stage = ConversationStage.ASK_TIME;
      } else {
        data.stage = ConversationStage.ASK_PERIOD;
        return "Perfeito. Voce prefere manha, tarde ou noite?";
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
        return "Sem problema. Qual novo dia voce deseja?";
      }

      if (data.availableTimeOptions.isEmpty()) {
        data.availableTimeOptions = new ArrayList<>(domainService.suggestTimes(tenantId, data.professionalId, data.date, data.serviceId, data.preferredPeriod));
      }
      if (data.availableTimeOptions.isEmpty()) {
        data.stage = ConversationStage.ASK_TIME;
        return "Nao encontrei horarios vagos para " + data.preferredPeriod.label()
            + " nessa data. Voce pode escolher outro periodo (manha/tarde/noite) ou trocar o dia.";
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
            return "Escolha apenas um horario vago de " + data.preferredPeriod.label() + ": "
                + buildNumberedTimeList(data.availableTimeOptions)
                + " Se quiser, digite outro periodo (manha/tarde/noite) ou 'trocar dia'.";
          }
        }
      }

      if (data.time == null) {
        data.stage = ConversationStage.ASK_TIME;
        return "Escolha um horario vago de " + data.preferredPeriod.label() + " pelo numero ou horario: "
            + buildNumberedTimeList(data.availableTimeOptions)
            + " Se quiser, digite outro periodo (manha/tarde/noite) ou 'trocar dia'.";
      }
    }

    if (!domainService.isSlotAvailable(tenantId, data.professionalId, data.date, data.time, data.serviceId)) {
      data.time = null;
      data.stage = ConversationStage.ASK_TIME;
      data.availableTimeOptions = new ArrayList<>(domainService.suggestTimes(tenantId, data.professionalId, data.date, data.serviceId, data.preferredPeriod));
      if (data.availableTimeOptions.isEmpty()) {
        return "Esse horario ficou indisponivel e nao ha vagas nesse periodo. "
            + "Escolha outro periodo (manha/tarde/noite) ou troque o dia.";
      }
      return "Esse horario ficou indisponivel. Escolha um dos horarios vagos de " + data.preferredPeriod.label()
          + ": " + buildNumberedTimeList(data.availableTimeOptions);
    }

    if (data.appointmentId == null) {
      if (data.sourceAppointmentId == null && !domainService.canScheduleViaWhatsApp(tenantId)) {
        resetFlowKeepCustomer(data);
        data.stage = ConversationStage.START;
        return "Este salao nao permite novos agendamentos pelo WhatsApp no momento.";
      }
      if (data.sourceAppointmentId != null && !domainService.canRescheduleViaWhatsApp(tenantId)) {
        resetFlowKeepCustomer(data);
        data.stage = ConversationStage.START;
        return "Este salao nao permite remarcacoes pelo WhatsApp no momento.";
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
          return "Esse horario acabou de ser reservado e nao ha mais vagas nesse periodo. "
              + "Escolha outro periodo (manha/tarde/noite) ou troque o dia.";
        }
        return "Esse horario acabou de ser reservado por outro cliente. Escolha um novo horario de "
            + data.preferredPeriod.label() + ": " + buildNumberedTimeList(data.availableTimeOptions);
      }
    }

    data.stage = ConversationStage.CONFIRMATION;
    return String.format(Locale.ROOT,
        "Confirma este agendamento? Servico: %s, profissional: %s, data: %s, horario: %s. Responda SIM ou NAO.",
        data.serviceName,
        data.professionalName,
        data.date,
        data.time);
  }

  private String handleCorrections(ConversationData data, String normalizedMessage, String tenantId) {
    if (wantsChangeDay(normalizedMessage)) {
      if (data.serviceId == null || data.professionalId == null) {
        return "Primeiro vamos definir servico e profissional, depois posso trocar o dia.";
      }
      cancelPendingIfExists(data, tenantId);
      data.date = null;
      data.time = null;
      data.preferredPeriod = null;
      data.availableTimeOptions.clear();
      data.stage = ConversationStage.ASK_DATE;
      return "Sem problema. Qual novo dia voce deseja?";
    }

    if (isRestartCommand(normalizedMessage)) {
      cancelPendingIfExists(data, tenantId);
      String customerName = data.customerName;
      data.reset();
      data.customerName = customerName;
      data.stage = ConversationStage.ASK_SERVICE;
      return "Perfeito, reiniciei este atendimento. " + domainService.formatServicesPrompt(tenantId);
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
      return "Sem problema. Qual servico voce quer agora?";
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
          ? "Primeiro me informe o servico."
          : "Sem problema. " + buildProfessionalPrompt(data, tenantId);
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

  private String buildProfessionalPrompt(ConversationData data, String tenantId) {
    List<ProfissionalDto> options = domainService.listProfessionalsByService(tenantId, data.serviceId);
    if (options.isEmpty()) {
      return "Nao encontrei profissionais ativos para esse servico.";
    }

    data.professionalOptionIds.clear();
    data.professionalOptionNames.clear();
    int limit = Math.min(options.size(), 10);
    for (int i = 0; i < limit; i++) {
      data.professionalOptionIds.add(options.get(i).id);
      data.professionalOptionNames.add(options.get(i).name);
    }

    return "Escolha o profissional pelo numero ou nome: " + buildNumberedProfessionalList(data.professionalOptionNames);
  }

  private String buildNumberedProfessionalList(List<String> names) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < names.size(); i++) {
      if (i > 0) out.append(", ");
      out.append(i + 1).append(" - ").append(names.get(i));
    }
    out.append(". Voce tambem pode responder 'ultimo'.");
    return out.toString();
  }

  private String buildNumberedTimeList(List<String> times) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < times.size(); i++) {
      if (i > 0) out.append("\n");
      out.append(i + 1).append(" - ").append(times.get(i));
    }
    out.append("\nVoce tambem pode responder 'ultimo'.");
    return out.toString();
  }

  private String buildNumberedAppointmentList(List<String> labels) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < labels.size(); i++) {
      if (i > 0) out.append(", ");
      out.append(i + 1).append(" - ").append(labels.get(i));
    }
    out.append(". Voce tambem pode responder 'ultimo'.");
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
    if (trimmed.length() < 3 || trimmed.length() > 80) return null;

    // Rejeita strings com 3 ou mais dígitos (telefones, datas, códigos)
    // mas permite nomes como "João 2º" ou "Ana 3ª"
    long digitCount = trimmed.chars().filter(Character::isDigit).count();
    if (digitCount > 2) return null;

    String normalized = TextNormalizer.normalize(trimmed);
    if (normalized.isBlank()) return null;
    if (DateTimeRegexExtractor.isAffirmative(normalized) || DateTimeRegexExtractor.isNegative(normalized)) return null;
    if (normalized.matches("^\\d+$")) return null;

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
      return "Voce nao possui agendamentos ativos para cancelar no momento.";
    }

    fillAppointmentOptions(data, options);
    data.stage = ConversationStage.ASK_CANCEL_APPOINTMENT;
    return "Qual agendamento voce quer cancelar? " + buildNumberedAppointmentList(data.appointmentOptionLabels);
  }

  private String iniciarFluxoRemarcacao(ConversationData data, String userIdentifier, String tenantId) {
    clearBookingFlow(data, tenantId);
    List<AssistantDomainService.UpcomingAppointmentOption> options =
        domainService.listUpcomingOptionsForUser(tenantId, userIdentifier, 10);
    if (options.isEmpty()) {
      data.stage = ConversationStage.START;
      return "Voce nao possui agendamentos ativos para remarcar no momento.";
    }

    fillAppointmentOptions(data, options);
    data.stage = ConversationStage.ASK_RESCHEDULE_APPOINTMENT;
    return "Qual agendamento voce quer remarcar? " + buildNumberedAppointmentList(data.appointmentOptionLabels);
  }

  private void fillAppointmentOptions(ConversationData data, List<AssistantDomainService.UpcomingAppointmentOption> options) {
    data.appointmentOptionIds.clear();
    data.appointmentOptionLabels.clear();
    for (AssistantDomainService.UpcomingAppointmentOption item : options) {
      data.appointmentOptionIds.add(item.appointmentId.toString());
      data.appointmentOptionLabels.add(
          item.serviceName + " com " + item.professionalName + " em " + item.date + " as " + item.time);
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
        return greetingBase + " Que bom falar com voce de novo. " + domainService.formatServicesPrompt(tenantId);
      }
      data.stage = ConversationStage.ASK_NAME;
      return greetingBase + " Que bom falar com voce. Para comecar, me informe seu nome.";
    }
    if (data.stage == ConversationStage.ASK_NAME) {
      return greetingBase + " Para eu continuar, me diga seu nome completo.";
    }
    if (data.stage == ConversationStage.ASK_SERVICE) {
      return greetingBase + " Vamos agendar. " + domainService.formatServicesPrompt(tenantId);
    }
    if (data.stage == ConversationStage.ASK_PROFESSIONAL) {
      return greetingBase + " Agora me diga qual profissional voce prefere.";
    }
    if (data.stage == ConversationStage.ASK_DATE) {
      return greetingBase + " Qual data voce deseja para o atendimento?";
    }
    if (data.stage == ConversationStage.ASK_PERIOD) {
      return greetingBase + " Para esse dia, prefere manha, tarde ou noite?";
    }
    if (data.stage == ConversationStage.ASK_TIME) {
      String periodLabel = data.preferredPeriod != null ? data.preferredPeriod.label() : "seu periodo escolhido";
      return greetingBase + " Me informe o horario desejado dentre os horarios vagos de " + periodLabel + ".";
    }
    if (data.stage == ConversationStage.ASK_CANCEL_APPOINTMENT) {
      return greetingBase + " Escolha qual agendamento voce quer cancelar pelo numero.";
    }
    if (data.stage == ConversationStage.ASK_RESCHEDULE_APPOINTMENT) {
      return greetingBase + " Escolha qual agendamento voce quer remarcar pelo numero.";
    }
    return greetingBase + " Vamos continuar seu agendamento.";
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
      return "Voltando uma etapa. Voce prefere manha, tarde ou noite?";
    }
    if (data.stage == ConversationStage.ASK_PERIOD) {
      data.preferredPeriod = null;
      data.time = null;
      data.stage = ConversationStage.ASK_DATE;
      return "Voltando uma etapa. Qual data voce deseja?";
    }
    if (data.stage == ConversationStage.ASK_DATE) {
      data.date = null;
      data.preferredPeriod = null;
      data.time = null;
      data.professionalId = null;
      data.professionalName = null;
      data.stage = ConversationStage.ASK_PROFESSIONAL;
      return data.serviceId == null
          ? "Voltando uma etapa. Qual servico voce quer?"
          : "Voltando uma etapa. " + buildProfessionalPrompt(data, tenantId);
    }
    if (data.stage == ConversationStage.ASK_PROFESSIONAL) {
      data.professionalId = null;
      data.professionalName = null;
      data.date = null;
      data.preferredPeriod = null;
      data.time = null;
      data.stage = ConversationStage.ASK_SERVICE;
      return "Voltando uma etapa. Qual servico voce quer agora?";
    }
    if (data.stage == ConversationStage.ASK_SERVICE) {
      data.serviceId = null;
      data.serviceName = null;
      data.stage = ConversationStage.ASK_NAME;
      return "Voltando uma etapa. Me diga seu nome completo.";
    }
    if (data.stage == ConversationStage.ASK_NAME) {
      return "Voce ja esta na primeira etapa. Me diga seu nome completo para continuar.";
    }

    data.stage = ConversationStage.ASK_SERVICE;
    return "Vamos retomar. " + domainService.formatServicesPrompt(tenantId);
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

  private String handleLowConfidenceIntent(ConversationData data, IntentPrediction intentPrediction, boolean prioritizeSlotInput) {
    if (prioritizeSlotInput) return null;
    if (data.stage != ConversationStage.START && data.stage != ConversationStage.COMPLETED) return null;
    if (intentPrediction.intent == IntentType.GREETING) return null;
    if (intentPrediction.intent != IntentType.UNKNOWN && intentPrediction.confidence >= minIntentConfidence) return null;

    return "Quero te ajudar sem errar. O que voce deseja agora? "
        + "1 - Agendar, 2 - Remarcar, 3 - Cancelar, 4 - Ver meus agendamentos.";
  }

  private boolean hasWord(String text, String word) {
    if (text == null || word == null || word.isBlank()) return false;
    String[] parts = text.split("\\s+");
    for (String part : parts) {
      if (word.equals(part)) return true;
    }
    return false;
  }
}
