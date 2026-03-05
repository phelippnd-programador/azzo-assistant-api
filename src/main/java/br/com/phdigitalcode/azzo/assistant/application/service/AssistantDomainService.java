package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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

@ApplicationScoped
public class AssistantDomainService {

  private static final String STATUS_CONFIRMED = "CONFIRMED";
  private static final String STATUS_CANCELLED = "CANCELLED";
  private static final String STATUS_PENDING = "PENDING";
  private static final String CHANNEL_ASSISTANT_CONFIRMATION = "ASSISTANT_CONFIRMATION";
  private static final String CHANNEL_ASSISTANT_CANCELLATION = "ASSISTANT_CANCELLATION";
  private static final String STATUS_NOTIF_SENT = "SENT";

  @Inject
  @RestClient
  AgendaProInternalClient agendaProClient;

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
    List<ServicoDto> services = listServices(tenantId);
    if (services.isEmpty()) {
      return "No momento nao ha servicos disponiveis para agendamento.";
    }
    String list = services.stream().map(s -> s.name).limit(10).collect(Collectors.joining(", "));
    return "Me diga qual servico voce quer agendar. Opcoes: " + list;
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
            scoreMatch(TextNormalizer.normalize(right.name), normalized),
            scoreMatch(TextNormalizer.normalize(left.name), normalized)))
        .filter(p -> scoreMatch(TextNormalizer.normalize(p.name), normalized) > 0)
        .findFirst();
  }

  // ─── Slots disponíveis ────────────────────────────────────────────────────

  public boolean isSlotAvailable(String tenantId, UUID professionalId, LocalDate date, String startTime, UUID serviceId) {
    try {
      int duration = resolveServiceDuration(tenantId, serviceId);
      List<TimeSlotDto> slots = agendaProClient.buscarSlotsDisponiveis(
          tenantId,
          professionalId.toString(),
          date.toString(),
          duration,
          0);
      return slots.stream().anyMatch(s -> startTime.equals(s.startTime) && s.available);
    } catch (RuntimeException ignored) {
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
          duration,
          0);
      return slots.stream()
          .filter(s -> s.available)
          .map(s -> s.startTime)
          .filter(t -> period == null || belongsToPeriod(t, period))
          .collect(Collectors.toList());
    } catch (RuntimeException ignored) {
      return List.of();
    }
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
        case MORNING -> !t.isAfter(LocalTime.NOON);
        case AFTERNOON -> t.isAfter(LocalTime.NOON) && !t.isAfter(LocalTime.of(18, 0));
        case NIGHT -> t.isAfter(LocalTime.of(18, 0));
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
    req.serviceId = serviceId.toString();
    req.professionalId = professionalId.toString();
    req.clientId = client.id;
    req.date = date.toString();
    req.startTime = time;
    req.status = STATUS_PENDING;
    req.notes = "Criado pelo assistant";

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
      return "Nao encontrei agendamentos para este contato.";
    }

    StringBuilder out = new StringBuilder("Seus proximos agendamentos:\n");
    for (UpcomingAppointmentOption option : options) {
      out.append("- ")
          .append(option.serviceName)
          .append(" com ")
          .append(option.professionalName)
          .append(" em ")
          .append(option.date)
          .append(" as ")
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
