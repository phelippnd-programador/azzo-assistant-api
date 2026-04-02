package br.com.phdigitalcode.azzo.assistant.infrastructure.client;

import java.util.List;

import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.AgendamentoCreateDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.AgendamentoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.AgendamentoStatusDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ClienteCreateDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ClienteDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.NotificacaoCreateDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ProfissionalDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ServicoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.TimeSlotDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.HorarioFuncionamentoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.SalonInfoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.WhatsAppPermissoesDto;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client para os endpoints internos do backend principal.
 * Todas as chamadas incluem automaticamente o header X-Internal-Api-Key.
 */
@RegisterRestClient(configKey = "agenda-pro")
@ClientHeaderParam(name = "X-Internal-Api-Key", value = "${app.internal.api-key}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface AgendaProInternalClient {

  // ─── Serviços ────────────────────────────────────────────────────────────

  @GET
  @Path("/api/v1/internal/assistant/services")
  List<ServicoDto> listarServicos(@QueryParam("tenantId") String tenantId);

  // ─── Profissionais ───────────────────────────────────────────────────────

  @GET
  @Path("/api/v1/internal/assistant/professionals")
  List<ProfissionalDto> listarProfissionais(
      @QueryParam("tenantId") String tenantId,
      @QueryParam("serviceId") String serviceId);

  // ─── Slots disponíveis ───────────────────────────────────────────────────

  @GET
  @Path("/api/v1/internal/assistant/available-slots")
  List<TimeSlotDto> buscarSlotsDisponiveis(
      @QueryParam("tenantId") String tenantId,
      @QueryParam("professionalId") String professionalId,
      @QueryParam("date") String date,
      @QueryParam("serviceIds") String serviceIds,
      @QueryParam("duration") int duration,
      @QueryParam("buffer") int buffer);

  // ─── Agendamentos ────────────────────────────────────────────────────────

  @POST
  @Path("/api/v1/internal/assistant/appointments")
  AgendamentoDto criarAgendamento(
      @QueryParam("tenantId") String tenantId,
      AgendamentoCreateDto request);

  @PATCH
  @Path("/api/v1/internal/assistant/appointments/{id}/status")
  void atualizarStatusAgendamento(
      @PathParam("id") String id,
      @QueryParam("tenantId") String tenantId,
      AgendamentoStatusDto request);

  @GET
  @Path("/api/v1/internal/assistant/clients/{clientId}/appointments")
  List<AgendamentoDto> listarAgendamentosCliente(
      @PathParam("clientId") String clientId,
      @QueryParam("tenantId") String tenantId,
      @QueryParam("limit") int limit);

  // ─── Clientes ────────────────────────────────────────────────────────────

  @GET
  @Path("/api/v1/internal/assistant/clients/search")
  ClienteDto buscarClientePorIdentificador(
      @QueryParam("tenantId") String tenantId,
      @QueryParam("identifier") String identifier);

  @POST
  @Path("/api/v1/internal/assistant/clients")
  ClienteDto criarCliente(
      @QueryParam("tenantId") String tenantId,
      ClienteCreateDto request);

  // ─── Notificações ────────────────────────────────────────────────────────

  @POST
  @Path("/api/v1/internal/assistant/notifications")
  void criarNotificacao(
      @QueryParam("tenantId") String tenantId,
      NotificacaoCreateDto request);

  // ─── Permissões WhatsApp ─────────────────────────────────────────────────

  @GET
  @Path("/api/v1/internal/assistant/tenant/whatsapp-permissions")
  WhatsAppPermissoesDto obterPermissoesWhatsApp(@QueryParam("tenantId") String tenantId);

  // ─── Info do Tenant ──────────────────────────────────────────────────────

  @GET
  @Path("/api/v1/internal/assistant/tenant/info")
  SalonInfoDto obterInfoTenant(@QueryParam("tenantId") String tenantId);

  // ─── Horários de Funcionamento ───────────────────────────────────────────

  @GET
  @Path("/api/v1/internal/assistant/tenant/business-hours")
  List<HorarioFuncionamentoDto> listarHorariosFuncionamento(@QueryParam("tenantId") String tenantId);
}
