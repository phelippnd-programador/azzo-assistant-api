package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    String normalized = TextNormalizer.normalize(candidate);
    if (normalized.isBlank()) return Optional.empty();

    return listServices(tenantId).stream()
        .sorted((left, right) -> Integer.compare(
            scoreMatch(TextNormalizer.normalize(right.name), normalized),
            scoreMatch(TextNormalizer.normalize(left.name), normalized)))
        .filter(s -> scoreMatch(TextNormalizer.normalize(s.name), normalized) > 0)
        .findFirst();
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
    if (value.equals(candidate)) return 3;
    if (value.contains(candidate) || candidate.contains(value)) return 2;
    if (value.split(" ")[0].equals(candidate.split(" ")[0])) return 1;
    return 0;
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
