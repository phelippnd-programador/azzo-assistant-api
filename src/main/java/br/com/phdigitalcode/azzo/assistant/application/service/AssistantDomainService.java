package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import br.com.phdigitalcode.azzo.assistant.dialogue.TimePeriod;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.AgendaProInternalClient;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.AgendamentoCreateDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.AgendamentoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.AgendamentoStatusDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ClienteCreateDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ClienteDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.NotificacaoCreateDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ProfissionalDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ServicoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.TimeSlotDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.WhatsAppPermissoesDto;
import br.com.phdigitalcode.azzo.assistant.util.TextNormalizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import br.com.phdigitalcode.azzo.assistant.llm.OllamaResponseService;

@ApplicationScoped
public class AssistantDomainService {

  private static final Logger LOG = Logger.getLogger(AssistantDomainService.class);

  private static final String STATUS_CONFIRMED = "CONFIRMED";
  private static final String STATUS_CANCELLED = "CANCELLED";
  private static final String STATUS_PENDING = "PENDING";
  private static final String CHANNEL_ASSISTANT_CONFIRMATION = "ASSISTANT_CONFIRMATION";
  private static final String CHANNEL_ASSISTANT_CANCELLATION = "ASSISTANT_CANCELLATION";
  private static final String STATUS_NOTIF_SENT = "SENT";
  private static final Set<String> SERVICE_INTENT_STOP_WORDS = Set.of(
      "quero", "queria", "gostaria", "preciso", "precisar", "fazer", "fazeria", "algo",
      "servico", "servicos", "atendimento", "trabalho", "trabalhar", "agendar", "marcar",
      "meu", "minha", "pro", "pra", "para", "com", "sem", "um", "uma", "o", "a", "os",
      "as", "de", "do", "da", "dos", "das", "no", "na", "nos", "nas", "e", "ou");

  @Inject
  @RestClient
  AgendaProInternalClient agendaProClient;

  @Inject
  OllamaResponseService ollamaResponseService;

  // ─── Serviços ─────────────────────────────────────────────────────────────

  public List<ServicoDto> listServices(String tenantId) {
    return agendaProClient.listarServicos(tenantId)
        .stream()
        .filter(s -> s.isActive)
        .toList();
  }

  public Optional<ServicoDto> resolveService(String tenantId, String candidate) {
    ServiceIntentContext intent = buildServiceIntentContext(candidate);
    if (intent.searchTerms().isEmpty()) return Optional.empty();

    List<ScoredServiceMatch> matches = scoreServices(tenantId, intent, 3);
    if (matches.isEmpty()) return Optional.empty();

    ScoredServiceMatch best = matches.get(0);
    ScoredServiceMatch second = matches.size() > 1 ? matches.get(1) : null;
    boolean exactNameMatch = TextNormalizer.normalize(best.service().name).equals(intent.normalizedCandidate());
    if (intent.ambiguousTopic() && !exactNameMatch) {
      return Optional.empty();
    }
    boolean strongEnough = best.score() >= 65;
    boolean clearlyAhead = second == null || best.score() >= second.score() + 20;

    if (exactNameMatch || (strongEnough && clearlyAhead)) {
      return Optional.of(best.service());
    }
    return Optional.empty();
  }

  public List<ServicoDto> findMatchingServices(String tenantId, String candidate, int limit) {
    ServiceIntentContext intent = buildServiceIntentContext(candidate);
    if (intent.searchTerms().isEmpty()) return List.of();

    int normalizedLimit = Math.max(1, Math.min(limit, 10));
    return scoreServices(tenantId, intent, normalizedLimit).stream()
        .map(ScoredServiceMatch::service)
        .toList();
  }

  public String formatServicesPrompt(String tenantId) {
    return formatServicesPromptForCustomer(tenantId, null);
  }

  public String formatServicesPromptForCustomer(String tenantId, String customerName) {
    List<ServicoDto> services = listServices(tenantId);
    if (services.isEmpty()) {
      return "Hmm, não tem serviços disponíveis agora. 😕";
    }
    // Tenta gerar resposta humanizada via LLM
    Optional<String> llmReply = ollamaResponseService.generateServicesMessage(customerName, services);
    if (llmReply.isPresent()) {
      return llmReply.get();
    }
    // Fallback enriquecido: mostra preço, duração e descrição quando disponíveis
    StringBuilder sb = new StringBuilder("Qual serviço você quer? 💅\n");
    int idx = 1;
    for (ServicoDto s : services.stream().limit(10).toList()) {
      sb.append("\n").append(idx++).append(" - ").append(s.name);
      if (s.price > 0) sb.append(" — R$").append(String.format(Locale.ROOT, "%.0f", s.price));
      if (s.duration > 0) sb.append(" | ").append(formatDurationMinutes(s.duration));
      if (s.description != null && !s.description.isBlank()) {
        sb.append("\n   ").append(s.description.trim());
      }
    }
    sb.append("\n\nManda o número ou o nome do serviço! 😊");
    return sb.toString();
  }

  public String formatServiceOptionsPrompt(String tenantId, String customerName, String candidate, List<ServicoDto> services) {
    if (services == null || services.isEmpty()) {
      return formatServicesPromptForCustomer(tenantId, customerName);
    }

    ServiceIntentContext intent = buildServiceIntentContext(candidate);
    StringBuilder sb = new StringBuilder();
    if (customerName != null && !customerName.isBlank()) {
      sb.append("Entendi, ").append(customerName).append(". ");
    } else {
      sb.append("Entendi. ");
    }
    sb.append("Nao consegui identificar exatamente o servico");
    if (!intent.humanLabel().isBlank()) {
      sb.append(" que voce quer");
      sb.append(", mas pelo que voce descreveu parece algo de ").append(intent.humanLabel());
    }
    sb.append(". Tenho estas opcoes:\n");

    int idx = 1;
    for (ServicoDto s : services.stream().limit(5).toList()) {
      sb.append("\n").append(idx++).append(" - ").append(s.name);
      if (s.price > 0) sb.append(" — R$ ").append(String.format(Locale.ROOT, "%.2f", s.price / 100.0).replace('.', ','));
      if (s.duration > 0) sb.append(" | ").append(formatDurationMinutes(s.duration));
      if (s.description != null && !s.description.isBlank()) {
        sb.append("\n   ").append(s.description.trim());
      }
    }

    sb.append("\n\nSe for um destes, me manda o numero ou o nome. Se nao, me descreve um pouco melhor.");
    return sb.toString();
  }

  private String formatDurationMinutes(int minutes) {
    if (minutes < 60) return minutes + "min";
    int h = minutes / 60;
    int m = minutes % 60;
    return m == 0 ? h + "h" : h + "h" + m + "min";
  }

  // ─── Profissionais ────────────────────────────────────────────────────────

  public List<ProfissionalDto> listProfessionals(String tenantId) {
    return agendaProClient.listarProfissionais(tenantId, null)
        .stream()
        .filter(p -> p.isActive)
        .toList();
  }

  public List<ProfissionalDto> listProfessionalsByService(String tenantId, UUID serviceId) {
    String serviceIdStr = serviceId != null ? serviceId.toString() : null;
    return agendaProClient.listarProfissionais(tenantId, serviceIdStr)
        .stream()
        .filter(p -> p.isActive)
        .toList();
  }

  public Optional<ProfissionalDto> resolveProfessional(String tenantId, String candidate, UUID serviceId) {
    String normalized = TextNormalizer.normalize(candidate);
    if (normalized.isBlank()) return Optional.empty();

    return listProfessionalsByService(tenantId, serviceId).stream()
        .sorted((left, right) -> Integer.compare(
            scoreMatchProfessional(right, normalized),
            scoreMatchProfessional(left, normalized)))
        .filter(p -> scoreMatchProfessional(p, normalized) > 0)
        .findFirst();
  }

  /**
   * Pontua o match de um profissional contra o texto candidato.
   * Considera tanto o nome do profissional quanto o nome das suas especialidades,
   * retornando o maior score entre eles.
   */
  private int scoreMatchProfessional(ProfissionalDto p, String normalized) {
    int nameScore = scoreMatch(TextNormalizer.normalize(p.name), normalized);
    if (p.specialtiesDetailed == null || p.specialtiesDetailed.isEmpty()) return nameScore;
    int specialtyScore = p.specialtiesDetailed.stream()
        .mapToInt(s -> scoreMatch(TextNormalizer.normalize(s.name), normalized))
        .max()
        .orElse(0);
    return Math.max(nameScore, specialtyScore);
  }

  /**
   * Retorna o nome da primeira especialidade do profissional, ou {@code null} se não houver.
   * Usado para enriquecer a mensagem de confirmação de agendamento.
   */
  public String firstSpecialtyName(ProfissionalDto p) {
    if (p == null || p.specialtiesDetailed == null || p.specialtiesDetailed.isEmpty()) return null;
    return p.specialtiesDetailed.get(0).name;
  }

  // ─── Slots disponíveis ────────────────────────────────────────────────────

  public boolean isSlotAvailable(String tenantId, UUID professionalId, LocalDate date, String startTime, UUID serviceId) {
    try {
      int duration = resolveServiceDuration(tenantId, serviceId);
      List<TimeSlotDto> slots = agendaProClient.buscarSlotsDisponiveis(
          tenantId,
          professionalId.toString(),
          date.toString(),
          serviceId != null ? serviceId.toString() : null,
          duration,
          0);
      // Normaliza ambos os lados para "HH:mm" — API pode retornar "HH:mm:ss"
      String normalizedTarget = normalizeSlotTime(startTime);
      return slots.stream()
          .filter(s -> s.available)
          .anyMatch(s -> normalizedTarget.equals(normalizeSlotTime(s.startTime)));
    } catch (RuntimeException e) {
      LOG.warnf("[Slots] Erro ao verificar disponibilidade: tenant=%s prof=%s data=%s horario=%s erro=%s",
          tenantId, professionalId, date, startTime, e.getMessage());
      return false;
    }
  }

  public List<String> suggestTimes(String tenantId, UUID professionalId, LocalDate date, UUID serviceId, TimePeriod period) {
    try {
      int duration = resolveServiceDuration(tenantId, serviceId);
      List<TimeSlotDto> slots = agendaProClient.buscarSlotsDisponiveis(
          tenantId,
          professionalId.toString(),
          date.toString(),
          serviceId != null ? serviceId.toString() : null,
          duration,
          0);
      return slots.stream()
          .filter(s -> s.available)
          // Filtra por período ANTES do map — ainda temos acesso ao startTime original
          .filter(s -> period == null || belongsToPeriod(normalizeSlotTime(s.startTime), period))
          // Melhores horários primeiro (score desc); empate → ordem cronológica
          .sorted(Comparator.comparingInt((TimeSlotDto s) -> s.optimizationScore).reversed()
              .thenComparing(s -> normalizeSlotTime(s.startTime)))
          .map(s -> normalizeSlotTime(s.startTime))   // garante formato "HH:mm" para o usuário
          .distinct()                                  // remove duplicatas (segurança)
          .limit(12)                                   // máximo 12 horários no WhatsApp
          .collect(Collectors.toList());
    } catch (RuntimeException e) {
      LOG.warnf("[Slots] Erro ao buscar horários disponíveis: tenant=%s prof=%s data=%s erro=%s",
          tenantId, professionalId, date, e.getMessage());
      return List.of();
    }
  }

  /** Normaliza horário para "HH:mm", removendo segundos se presentes ("09:00:00" → "09:00"). */
  private String normalizeSlotTime(String time) {
    if (time == null || time.length() <= 5) return time == null ? "" : time;
    return time.substring(0, 5);
  }

  private int resolveServiceDuration(String tenantId, UUID serviceId) {
    if (serviceId == null) return 30;
    return listServices(tenantId).stream()
        .filter(s -> serviceId.toString().equals(s.id))
        .map(s -> s.duration)
        .findFirst()
        .orElse(30);
  }

  private boolean belongsToPeriod(String startTime, TimePeriod period) {
    try {
      LocalTime t = LocalTime.parse(startTime.length() == 5 ? startTime : startTime.substring(0, 5));
      return switch (period) {
        // MANHÃ: estritamente antes das 12h (12:00 é tarde, não manhã)
        case MORNING   -> t.isBefore(LocalTime.NOON);
        // TARDE: 12:00 até 17:59 (18:00 já é noite)
        case AFTERNOON -> !t.isBefore(LocalTime.NOON) && t.isBefore(LocalTime.of(18, 0));
        // NOITE: 18:00 em diante
        case NIGHT     -> !t.isBefore(LocalTime.of(18, 0));
      };
    } catch (RuntimeException ignored) {
      return true;
    }
  }

  // ─── Agendamentos ─────────────────────────────────────────────────────────

  public UUID createPendingAppointment(
      String tenantId,
      UUID serviceId,
      UUID professionalId,
      LocalDate date,
      String time,
      String userIdentifier,
      String customerName) {

    if (!isSlotAvailable(tenantId, professionalId, date, time, serviceId)) {
      throw new IllegalStateException("Horario indisponivel para criacao do agendamento");
    }

    ClienteDto client = resolveOrCreateClient(tenantId, userIdentifier, customerName);

    AgendamentoCreateDto req = new AgendamentoCreateDto();
    req.professionalId = professionalId.toString();
    req.clientId = client.id;
    req.date = date.toString();
    req.startTime = time;
    req.status = STATUS_PENDING;
    req.notes = "Criado pelo assistant";
    AgendamentoCreateDto.ItemDto item = new AgendamentoCreateDto.ItemDto();
    item.serviceId = serviceId.toString();
    item.quantity = 1;
    item.unitPrice = 0;
    item.totalPrice = 0;
    req.items.add(item);

    AgendamentoDto created = agendaProClient.criarAgendamento(tenantId, req);
    return UUID.fromString(created.id);
  }

  public void confirmAppointment(String tenantId, UUID appointmentId, String userIdentifier) {
    agendaProClient.atualizarStatusAgendamento(
        appointmentId.toString(),
        tenantId,
        new AgendamentoStatusDto(STATUS_CONFIRMED));
    registrarNotificacaoConfirmacao(tenantId, appointmentId, userIdentifier);
  }

  public void cancelAppointment(String tenantId, UUID appointmentId, String userIdentifier) {
    registrarNotificacaoCancelamento(tenantId, appointmentId, userIdentifier);
    agendaProClient.atualizarStatusAgendamento(
        appointmentId.toString(),
        tenantId,
        new AgendamentoStatusDto(STATUS_CANCELLED));
  }

  // ─── Listagem de agendamentos do usuário ─────────────────────────────────

  public String listUpcomingForUser(String tenantId, String userIdentifier) {
    List<UpcomingAppointmentOption> options = listUpcomingOptionsForUser(tenantId, userIdentifier, 5);
    if (options.isEmpty()) {
      return "Não encontrei agendamentos pra você. 😊";
    }

    StringBuilder out = new StringBuilder("Seus próximos agendamentos: 📋\n");
    for (UpcomingAppointmentOption option : options) {
      out.append("- ")
          .append(option.serviceName)
          .append(" com ")
          .append(option.professionalName)
          .append(" em ")
          .append(option.date)
          .append(" às ")
          .append(option.time)
          .append("\n");
    }
    return out.toString().trim();
  }

  public List<UpcomingAppointmentOption> listUpcomingOptionsForUser(String tenantId, String userIdentifier, int limit) {
    ClienteDto client;
    try {
      client = agendaProClient.buscarClientePorIdentificador(tenantId, userIdentifier);
    } catch (RuntimeException ignored) {
      return List.of();
    }
    if (client == null || client.id == null) return List.of();

    int normalizedLimit = Math.max(1, Math.min(limit, 20));
    List<AgendamentoDto> appointments;
    try {
      appointments = agendaProClient.listarAgendamentosCliente(client.id, tenantId, normalizedLimit);
    } catch (RuntimeException ignored) {
      return List.of();
    }
    if (appointments == null || appointments.isEmpty()) return List.of();

    List<ServicoDto> services = listServices(tenantId);
    List<ProfissionalDto> professionals = listProfessionals(tenantId);

    List<UpcomingAppointmentOption> options = new ArrayList<>();
    for (AgendamentoDto ag : appointments) {
      String serviceName = services.stream()
          .filter(s -> s.id.equals(ag.serviceId))
          .map(s -> s.name)
          .findFirst()
          .orElse("Servico");
      String professionalName = professionals.stream()
          .filter(p -> p.id.equals(ag.professionalId))
          .map(p -> p.name)
          .findFirst()
          .orElse("Profissional");

      UpcomingAppointmentOption option = new UpcomingAppointmentOption();
      option.appointmentId = UUID.fromString(ag.id);
      option.serviceId = UUID.fromString(ag.serviceId);
      option.professionalId = UUID.fromString(ag.professionalId);
      option.serviceName = serviceName;
      option.professionalName = professionalName;
      option.date = LocalDate.parse(ag.date);
      option.time = ag.startTime;
      options.add(option);
    }
    return options;
  }

  public Optional<UpcomingAppointmentOption> findUpcomingOptionForUser(String tenantId, String userIdentifier, UUID appointmentId) {
    if (appointmentId == null) return Optional.empty();
    return listUpcomingOptionsForUser(tenantId, userIdentifier, 50).stream()
        .filter(item -> appointmentId.equals(item.appointmentId))
        .findFirst();
  }

  public void cancelAppointmentForUser(String tenantId, String userIdentifier, UUID appointmentId) {
    findUpcomingOptionForUser(tenantId, userIdentifier, appointmentId)
        .orElseThrow(() -> new IllegalArgumentException("Agendamento nao encontrado para este contato"));
    cancelAppointment(tenantId, appointmentId, userIdentifier);
  }

  // ─── Clientes ─────────────────────────────────────────────────────────────

  public Optional<String> resolveRegisteredCustomerName(String tenantId, String userIdentifier) {
    try {
      ClienteDto client = agendaProClient.buscarClientePorIdentificador(tenantId, userIdentifier);
      if (client != null && client.name != null && !client.name.isBlank()) {
        return Optional.of(client.name.trim());
      }
    } catch (RuntimeException ignored) {
    }
    return Optional.empty();
  }

  private ClienteDto resolveOrCreateClient(String tenantId, String userIdentifier, String customerName) {
    try {
      ClienteDto existing = agendaProClient.buscarClientePorIdentificador(tenantId, userIdentifier);
      if (existing != null && existing.id != null) {
        return existing;
      }
    } catch (RuntimeException ignored) {
    }

    String phone = null;
    String email = null;
    if (isPhone(userIdentifier)) {
      phone = normalizeDigits(userIdentifier);
    } else if (userIdentifier != null && userIdentifier.contains("@")) {
      email = userIdentifier.toLowerCase(Locale.ROOT);
    }

    String name = (customerName != null && !customerName.isBlank())
        ? customerName.trim()
        : "Cliente Assistente";

    return agendaProClient.criarCliente(tenantId, new ClienteCreateDto(name, phone, email, userIdentifier));
  }

  // ─── Permissões WhatsApp ──────────────────────────────────────────────────

  public boolean canScheduleViaWhatsApp(String tenantId) {
    return obterPermissoes(tenantId).canSchedule;
  }

  public boolean canCancelViaWhatsApp(String tenantId) {
    return obterPermissoes(tenantId).canCancel;
  }

  public boolean canRescheduleViaWhatsApp(String tenantId) {
    return obterPermissoes(tenantId).canReschedule;
  }

  private WhatsAppPermissoesDto obterPermissoes(String tenantId) {
    try {
      WhatsAppPermissoesDto permissoes = agendaProClient.obterPermissoesWhatsApp(tenantId);
      return permissoes != null ? permissoes : new WhatsAppPermissoesDto(true, true, true);
    } catch (RuntimeException ignored) {
      return new WhatsAppPermissoesDto(true, true, true);
    }
  }

  // ─── Notificações ─────────────────────────────────────────────────────────

  private void registrarNotificacaoConfirmacao(String tenantId, UUID appointmentId, String destination) {
    if (appointmentId == null) return;
    try {
      agendaProClient.criarNotificacao(tenantId, new NotificacaoCreateDto(
          appointmentId.toString(),
          CHANNEL_ASSISTANT_CONFIRMATION,
          destination,
          "Agendamento confirmado via assistente.",
          STATUS_NOTIF_SENT));
    } catch (RuntimeException ignored) {
    }
  }

  private void registrarNotificacaoCancelamento(String tenantId, UUID appointmentId, String destination) {
    if (appointmentId == null) return;
    try {
      agendaProClient.criarNotificacao(tenantId, new NotificacaoCreateDto(
          appointmentId.toString(),
          CHANNEL_ASSISTANT_CANCELLATION,
          destination,
          "Agendamento cancelado via assistente.",
          STATUS_NOTIF_SENT));
    } catch (RuntimeException ignored) {
    }
  }

  // ─── Utilitários ──────────────────────────────────────────────────────────

  private int scoreMatch(String value, String candidate) {
    if (value.equals(candidate)) return 40;
    if (value.contains(candidate) || candidate.contains(value)) return 24;
    if (value.split(" ")[0].equals(candidate.split(" ")[0])) return 10;
    return 0;
  }

  private List<ScoredServiceMatch> scoreServices(String tenantId, ServiceIntentContext intent, int limit) {
    int normalizedLimit = Math.max(1, Math.min(limit, 10));
    return listServices(tenantId).stream()
        .map(service -> new ScoredServiceMatch(service, scoreService(service, intent)))
        .filter(match -> match.score() > 0)
        .sorted((left, right) -> Integer.compare(right.score(), left.score()))
        .limit(normalizedLimit)
        .toList();
  }

  private int scoreService(ServicoDto service, ServiceIntentContext intent) {
    String normalizedName = TextNormalizer.normalize(service.name);
    String normalizedDescription = TextNormalizer.normalize(service.description);
    String normalizedCategory = TextNormalizer.normalize(service.category);
    List<String> serviceNameTokens = meaningfulTokens(normalizedName);
    List<String> serviceDescriptionTokens = meaningfulTokens(normalizedDescription);
    List<String> serviceCategoryTokens = meaningfulTokens(normalizedCategory);

    int score = scoreMatch(normalizedName, intent.normalizedCandidate());
    if (!intent.normalizedTopic().isBlank()) {
      score += scoreMatch(normalizedCategory, intent.normalizedTopic());
      score += scoreMatch(normalizedName, intent.normalizedTopic());
    }

    for (String term : intent.searchTerms()) {
      if (term.isBlank()) continue;
      if (normalizedName.equals(term)) {
        score += 80;
      } else if (normalizedName.contains(term)) {
        score += 30;
      } else if (normalizedCategory.contains(term)) {
        score += 18;
      } else if (normalizedDescription.contains(term)) {
        score += 10;
      }

      score += scoreTokenSimilarity(term, serviceNameTokens, 45);
      score += scoreTokenSimilarity(term, serviceCategoryTokens, 22);
      score += scoreTokenSimilarity(term, serviceDescriptionTokens, 12);
    }

    return score;
  }

  private ServiceIntentContext buildServiceIntentContext(String candidate) {
    String normalized = TextNormalizer.normalize(candidate);
    if (normalized.isBlank()) return new ServiceIntentContext("", "", "", List.of(), false);

    String topic = normalized;
    String label = "";
    boolean ambiguousTopic = meaningfulTokens(normalized).size() <= 1;
    Set<String> terms = new LinkedHashSet<>();
    terms.add(normalized);
    terms.addAll(meaningfulTokens(normalized));

    if ((normalized.contains("cortar") || normalized.contains("corte")) && normalized.contains("cabelo")) {
      topic = "corte";
      label = "corte";
      ambiguousTopic = false;
      terms.add("corte");
      terms.add("cabelo");
    }
    if (normalized.contains("barba") || normalized.contains("barbear")) {
      topic = "barba";
      label = "barba";
      ambiguousTopic = false;
      terms.add("barba");
    }
    if (normalized.contains("unha") || normalized.contains("manicure") || normalized.contains("pedicure")) {
      topic = "unha";
      label = "unhas";
      ambiguousTopic = false;
      terms.add("unha");
      terms.add("manicure");
      terms.add("pedicure");
    }
    if (normalized.contains("sobrancelha")) {
      topic = "sobrancelha";
      label = "sobrancelha";
      ambiguousTopic = false;
      terms.add("sobrancelha");
    }
    if (normalized.contains("escova")) {
      topic = "escova";
      label = "escova";
      ambiguousTopic = false;
      terms.add("escova");
    }
    if (normalized.contains("progressiva")) {
      topic = "progressiva";
      label = "progressiva";
      ambiguousTopic = false;
      terms.add("progressiva");
    }
    if (normalized.contains("hidratacao") || normalized.contains("hidratar")) {
      topic = "hidratacao";
      label = "hidratacao";
      ambiguousTopic = false;
      terms.add("hidratacao");
      terms.add("capilar");
    }
    if (normalized.contains("botox")) {
      topic = "botox";
      label = "botox capilar";
      ambiguousTopic = false;
      terms.add("botox");
      terms.add("capilar");
    }
    if (normalized.contains("coloracao") || normalized.contains("colorir")
        || normalized.contains("pintar") || normalized.contains("tintura")) {
      topic = "coloracao";
      label = "coloracao";
      ambiguousTopic = false;
      terms.add("coloracao");
      terms.add("raiz");
      terms.add("tinta");
    }
    if (normalized.contains("mechas")) {
      topic = "mechas";
      label = "mechas";
      ambiguousTopic = false;
      terms.add("mechas");
    }
    if (normalized.contains("luzes")) {
      topic = "luzes";
      label = "luzes";
      ambiguousTopic = false;
      terms.add("luzes");
    }
    if (normalized.contains("cabelo") || normalized.contains("capilar")) {
      if (label.isBlank()) {
        label = "cabelo";
        ambiguousTopic = true;
      }
      if (topic.equals(normalized)) topic = "cabelo";
      terms.add("cabelo");
      terms.add("capilar");
      if ("cabelo".equals(label)) {
        terms.add("corte");
        terms.add("escova");
        terms.add("coloracao");
        terms.add("hidratacao");
        terms.add("progressiva");
      }
    }
    if (normalized.contains("noiva") || normalized.contains("casamento")) {
      if (label.isBlank()) label = "noiva";
      if (topic.equals(normalized)) topic = "noiva";
      terms.add("noiva");
      terms.add("penteado");
      terms.add("maquiagem");
    }

    if (label.isBlank()) {
      label = topic.equals(normalized) ? "" : topic;
    }
    return new ServiceIntentContext(normalized, TextNormalizer.normalize(topic), label, List.copyOf(terms), ambiguousTopic);
  }

  private int scoreTokenSimilarity(String candidateToken, List<String> serviceTokens, int exactWeight) {
    if (candidateToken == null || candidateToken.isBlank() || serviceTokens.isEmpty()) return 0;

    String normalizedCandidateToken = normalizeToken(candidateToken);
    int best = 0;
    for (String serviceToken : serviceTokens) {
      String normalizedServiceToken = normalizeToken(serviceToken);
      if (normalizedServiceToken.isBlank()) continue;

      if (normalizedServiceToken.equals(normalizedCandidateToken)) {
        best = Math.max(best, exactWeight);
        continue;
      }
      if (normalizedServiceToken.contains(normalizedCandidateToken) || normalizedCandidateToken.contains(normalizedServiceToken)) {
        best = Math.max(best, Math.max(8, exactWeight - 14));
        continue;
      }

      double similarity = tokenSimilarity(normalizedCandidateToken, normalizedServiceToken);
      if (similarity >= 0.92d) {
        best = Math.max(best, Math.max(10, exactWeight - 8));
      } else if (similarity >= 0.80d) {
        best = Math.max(best, Math.max(7, exactWeight - 18));
      } else if (similarity >= 0.70d) {
        best = Math.max(best, Math.max(4, exactWeight - 26));
      }
    }
    return best;
  }

  private List<String> meaningfulTokens(String normalized) {
    if (normalized == null || normalized.isBlank()) return List.of();
    return Arrays.stream(normalized.split("[^a-z0-9]+"))
        .map(String::trim)
        .filter(token -> !token.isBlank())
        .filter(token -> !SERVICE_INTENT_STOP_WORDS.contains(token))
        .map(this::normalizeToken)
        .filter(token -> !token.isBlank())
        .distinct()
        .toList();
  }

  private String normalizeToken(String token) {
    if (token == null) return "";
    String normalized = TextNormalizer.normalize(token).replaceAll("[^a-z0-9]", "");
    if (normalized.length() > 4 && normalized.endsWith("oes")) {
      normalized = normalized.substring(0, normalized.length() - 3) + "ao";
    } else if (normalized.length() > 4 && normalized.endsWith("ais")) {
      normalized = normalized.substring(0, normalized.length() - 3) + "al";
    } else if (normalized.length() > 4 && normalized.endsWith("eis")) {
      normalized = normalized.substring(0, normalized.length() - 3) + "el";
    } else if (normalized.length() > 4 && normalized.endsWith("s")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }

    if (normalized.endsWith("r") && normalized.length() > 5) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (normalized.endsWith("ndo") && normalized.length() > 6) {
      normalized = normalized.substring(0, normalized.length() - 3);
    }
    return normalized;
  }

  private double tokenSimilarity(String left, String right) {
    if (left.equals(right)) return 1.0d;
    int maxLength = Math.max(left.length(), right.length());
    if (maxLength == 0) return 1.0d;
    return 1.0d - ((double) levenshteinDistance(left, right) / (double) maxLength);
  }

  private int levenshteinDistance(String left, String right) {
    int[] previous = new int[right.length() + 1];
    int[] current = new int[right.length() + 1];

    for (int j = 0; j <= right.length(); j++) {
      previous[j] = j;
    }

    for (int i = 1; i <= left.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= right.length(); j++) {
        int substitutionCost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(
            Math.min(current[j - 1] + 1, previous[j] + 1),
            previous[j - 1] + substitutionCost);
      }

      int[] swap = previous;
      previous = current;
      current = swap;
    }

    return previous[right.length()];
  }

  private static final class ServiceIntentContext {
    private final String normalizedCandidate;
    private final String normalizedTopic;
    private final String humanLabel;
    private final List<String> searchTerms;
    private final boolean ambiguousTopic;

    private ServiceIntentContext(String normalizedCandidate, String normalizedTopic, String humanLabel, List<String> searchTerms, boolean ambiguousTopic) {
      this.normalizedCandidate = normalizedCandidate;
      this.normalizedTopic = normalizedTopic;
      this.humanLabel = humanLabel;
      this.searchTerms = searchTerms;
      this.ambiguousTopic = ambiguousTopic;
    }

    private String normalizedCandidate() { return normalizedCandidate; }
    private String normalizedTopic() { return normalizedTopic; }
    private String humanLabel() { return humanLabel; }
    private List<String> searchTerms() { return searchTerms; }
    private boolean ambiguousTopic() { return ambiguousTopic; }
  }

  private static final class ScoredServiceMatch {
    private final ServicoDto service;
    private final int score;

    private ScoredServiceMatch(ServicoDto service, int score) {
      this.service = service;
      this.score = score;
    }

    private ServicoDto service() { return service; }
    private int score() { return score; }
  }

  private boolean isPhone(String identifier) {
    return normalizeDigits(identifier).length() >= 10;
  }

  private String normalizeDigits(String value) {
    if (value == null) return "";
    return value.replaceAll("\\D", "");
  }

  // ─── Inner class ─────────────────────────────────────────────────────────

  public static class UpcomingAppointmentOption {
    public UUID appointmentId;
    public UUID serviceId;
    public UUID professionalId;
    public String serviceName;
    public String professionalName;
    public LocalDate date;
    public String time;
  }
}
