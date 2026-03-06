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
import br.com.phdigitalcode.azzo.assistant.llm.OllamaDateEnricher;
import br.com.phdigitalcode.azzo.assistant.llm.OllamaIntentService;
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
  @Inject Instance<OllamaSlotExtractor> ollamaSlotExtractorInstance;
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
  @ConfigProperty(name = "assistant.ollama.min-confidence", defaultValue = "0.75")
  double ollamaMinConfidence;

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
      stateRepository.save(entity);
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
    return String.format(Locale.ROOT,
        "Tá quase! Confirma o agendamento?\n\n✂️ *%s*\n👤 %s\n📅 %s às %s\n\nResponde *SIM* pra confirmar ou *NÃO* pra cancelar.",
        data.serviceName,
        data.professionalName,
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

  private String buildProfessionalPrompt(ConversationData data, String tenantId) {
    List<ProfissionalDto> options = domainService.listProfessionalsByService(tenantId, data.serviceId);
    if (options.isEmpty()) {
      return "Hmm, não encontrei profissionais disponíveis pra esse serviço. 😕 Tenta outro?";
    }

    data.professionalOptionIds.clear();
    data.professionalOptionNames.clear();
    int limit = Math.min(options.size(), 10);
    for (int i = 0; i < limit; i++) {
      data.professionalOptionIds.add(options.get(i).id);
      data.professionalOptionNames.add(options.get(i).name);
    }

    return "Com quem você quer? Escolha pelo número ou nome:\n" + buildNumberedProfessionalList(data.professionalOptionNames);
  }

  private String buildNumberedProfessionalList(List<String> names) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < names.size(); i++) {
      if (i > 0) out.append("\n");
      out.append(i + 1).append(" - ").append(names.get(i));
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
