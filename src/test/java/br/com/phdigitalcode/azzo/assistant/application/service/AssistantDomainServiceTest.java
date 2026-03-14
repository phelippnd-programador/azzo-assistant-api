package br.com.phdigitalcode.azzo.assistant.application.service;

import br.com.phdigitalcode.azzo.assistant.dialogue.TimePeriod;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.AgendaProInternalClient;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssistantDomainService")
class AssistantDomainServiceTest {

    @Mock
    AgendaProInternalClient agendaProClient;

    @InjectMocks
    AssistantDomainService service;

    private static final String TENANT = "tenant-001";
    private static final UUID SERVICE_ID = UUID.randomUUID();
    private static final UUID PROFESSIONAL_ID = UUID.randomUUID();

    // ─── helpers ──────────────────────────────────────────────────────────────

    private ServicoDto servicoAtivo(String id, String name, int duration) {
        ServicoDto dto = new ServicoDto();
        dto.id = id;
        dto.name = name;
        dto.duration = duration;
        dto.isActive = true;
        return dto;
    }

    private ServicoDto servicoInativo(String id, String name) {
        ServicoDto dto = new ServicoDto();
        dto.id = id;
        dto.name = name;
        dto.duration = 30;
        dto.isActive = false;
        return dto;
    }

    private ProfissionalDto profissionalAtivo(String id, String name) {
        ProfissionalDto dto = new ProfissionalDto();
        dto.id = id;
        dto.name = name;
        dto.isActive = true;
        return dto;
    }

    private TimeSlotDto slot(String startTime, boolean available) {
        TimeSlotDto dto = new TimeSlotDto();
        dto.startTime = startTime;
        dto.available = available;
        return dto;
    }

    // ─── listServices ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("listServices: retorna apenas serviços ativos")
    void listServices_filtraInativos() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo("1", "Corte", 30),
                servicoInativo("2", "Manicure"),
                servicoAtivo("3", "Coloração", 90)
        ));

        List<ServicoDto> result = service.listServices(TENANT);

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(s -> s.isActive));
    }

    @Test
    @DisplayName("listServices: retorna lista vazia quando nenhum ativo")
    void listServices_semAtivos_retornaVazio() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoInativo("1", "Corte")
        ));

        assertTrue(service.listServices(TENANT).isEmpty());
    }

    // ─── resolveService ───────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveService: match exato retorna o serviço")
    void resolveService_matchExato() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Corte", 30)
        ));

        Optional<ServicoDto> result = service.resolveService(TENANT, "Corte");
        assertTrue(result.isPresent());
        assertEquals("Corte", result.get().name);
    }

    @Test
    @DisplayName("resolveService: match parcial retorna o serviço")
    void resolveService_matchParcial() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Corte de Cabelo", 30)
        ));

        Optional<ServicoDto> result = service.resolveService(TENANT, "Corte");
        assertTrue(result.isPresent());
    }

    @Test
    @DisplayName("resolveService: candidato sem match retorna empty")
    void resolveService_semMatch_retornaEmpty() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Manicure", 30)
        ));

        Optional<ServicoDto> result = service.resolveService(TENANT, "xyz-inexistente");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("resolveService: candidato em branco retorna empty")
    void resolveService_candidatoEmBranco_retornaEmpty() {
        Optional<ServicoDto> result = service.resolveService(TENANT, "   ");
        assertTrue(result.isEmpty());
        verifyNoInteractions(agendaProClient);
    }

    // ─── formatServicesPrompt ─────────────────────────────────────────────────

    @Test
    @DisplayName("formatServicesPrompt: com serviços lista as opções")
    void formatServicesPrompt_comServicos() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo("1", "Corte", 30),
                servicoAtivo("2", "Manicure", 45)
        ));

        String prompt = service.formatServicesPrompt(TENANT);
        assertTrue(prompt.contains("Corte"));
        assertTrue(prompt.contains("Manicure"));
    }

    @Test
    @DisplayName("formatServicesPrompt: sem serviços retorna mensagem de indisponibilidade")
    void formatServicesPrompt_semServicos() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of());

        String prompt = service.formatServicesPrompt(TENANT);
        assertTrue(prompt.toLowerCase().contains("servi"));
        assertTrue(prompt.toLowerCase().contains("dispon"));
    }

    // ─── isSlotAvailable ──────────────────────────────────────────────────────

    @Test
    @DisplayName("isSlotAvailable: retorna true quando slot livre existe")
    void isSlotAvailable_slotLivre_true() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Corte", 30)
        ));
        when(agendaProClient.buscarSlotsDisponiveis(eq(TENANT), eq(PROFESSIONAL_ID.toString()), anyString(), eq(SERVICE_ID.toString()), eq(30), eq(0)))
                .thenReturn(List.of(slot("09:00", true), slot("10:00", false)));

        assertTrue(service.isSlotAvailable(TENANT, PROFESSIONAL_ID, LocalDate.now().plusDays(1), "09:00", SERVICE_ID));
    }

    @Test
    @DisplayName("isSlotAvailable: retorna false quando slot está ocupado")
    void isSlotAvailable_slotOcupado_false() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Corte", 30)
        ));
        when(agendaProClient.buscarSlotsDisponiveis(eq(TENANT), eq(PROFESSIONAL_ID.toString()), anyString(), eq(SERVICE_ID.toString()), eq(30), eq(0)))
                .thenReturn(List.of(slot("09:00", false)));

        assertFalse(service.isSlotAvailable(TENANT, PROFESSIONAL_ID, LocalDate.now().plusDays(1), "09:00", SERVICE_ID));
    }

    @Test
    @DisplayName("isSlotAvailable: usa duração do serviço ao buscar slots")
    void isSlotAvailable_usaDuracaoDoServico() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Coloração", 90)
        ));
        when(agendaProClient.buscarSlotsDisponiveis(eq(TENANT), eq(PROFESSIONAL_ID.toString()), anyString(), eq(SERVICE_ID.toString()), eq(90), eq(0)))
                .thenReturn(List.of(slot("10:00", true)));

        assertTrue(service.isSlotAvailable(TENANT, PROFESSIONAL_ID, LocalDate.now().plusDays(1), "10:00", SERVICE_ID));
        verify(agendaProClient).buscarSlotsDisponiveis(
                TENANT,
                PROFESSIONAL_ID.toString(),
                LocalDate.now().plusDays(1).toString(),
                SERVICE_ID.toString(),
                90,
                0);
    }

    @Test
    @DisplayName("isSlotAvailable: exceção no client retorna false (fail-safe)")
    void isSlotAvailable_excecaoRetornaFalse() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Corte", 30)
        ));
        when(agendaProClient.buscarSlotsDisponiveis(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("timeout"));

        assertFalse(service.isSlotAvailable(TENANT, PROFESSIONAL_ID, LocalDate.now().plusDays(1), "10:00", SERVICE_ID));
    }

    // ─── suggestTimes ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("suggestTimes: filtra slots por período MORNING (até 12h)")
    void suggestTimes_filtraPorPeriodoManha() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Corte", 30)
        ));
        when(agendaProClient.buscarSlotsDisponiveis(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(
                        slot("08:00", true),
                        slot("10:00", true),
                        slot("14:00", true),
                        slot("20:00", true)
                ));

        List<String> result = service.suggestTimes(TENANT, PROFESSIONAL_ID, LocalDate.now().plusDays(1), SERVICE_ID, TimePeriod.MORNING);

        assertEquals(2, result.size());
        assertTrue(result.contains("08:00"));
        assertTrue(result.contains("10:00"));
        assertFalse(result.contains("14:00"));
    }

    @Test
    @DisplayName("suggestTimes: filtra slots por período AFTERNOON (12h-18h)")
    void suggestTimes_filtraPorPeriodoTarde() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Corte", 30)
        ));
        when(agendaProClient.buscarSlotsDisponiveis(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(
                        slot("09:00", true),
                        slot("13:00", true),
                        slot("17:00", true),
                        slot("19:00", true)
                ));

        List<String> result = service.suggestTimes(TENANT, PROFESSIONAL_ID, LocalDate.now().plusDays(1), SERVICE_ID, TimePeriod.AFTERNOON);

        assertEquals(2, result.size());
        assertTrue(result.contains("13:00"));
        assertTrue(result.contains("17:00"));
    }

    @Test
    @DisplayName("suggestTimes: retorna apenas slots disponíveis")
    void suggestTimes_filtraApenasSlotsDisponiveis() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Corte", 30)
        ));
        when(agendaProClient.buscarSlotsDisponiveis(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(
                        slot("09:00", true),
                        slot("10:00", false),
                        slot("11:00", true)
                ));

        List<String> result = service.suggestTimes(TENANT, PROFESSIONAL_ID, LocalDate.now().plusDays(1), SERVICE_ID, TimePeriod.MORNING);
        assertEquals(2, result.size());
        assertFalse(result.contains("10:00"));
    }

    @Test
    @DisplayName("suggestTimes: exceção no client retorna lista vazia (fail-safe)")
    void suggestTimes_excecaoRetornaVazio() {
        when(agendaProClient.listarServicos(TENANT)).thenReturn(List.of(
                servicoAtivo(SERVICE_ID.toString(), "Corte", 30)
        ));
        when(agendaProClient.buscarSlotsDisponiveis(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("timeout"));

        List<String> result = service.suggestTimes(TENANT, PROFESSIONAL_ID, LocalDate.now().plusDays(1), SERVICE_ID, TimePeriod.MORNING);
        assertTrue(result.isEmpty());
    }

    // ─── permissões WhatsApp ──────────────────────────────────────────────────

    @Test
    @DisplayName("canScheduleViaWhatsApp: retorna true quando permitido")
    void canSchedule_true() {
        WhatsAppPermissoesDto perm = new WhatsAppPermissoesDto(true, false, false);
        when(agendaProClient.obterPermissoesWhatsApp(TENANT)).thenReturn(perm);

        assertTrue(service.canScheduleViaWhatsApp(TENANT));
    }

    @Test
    @DisplayName("canCancelViaWhatsApp: retorna false quando negado")
    void canCancel_false() {
        WhatsAppPermissoesDto perm = new WhatsAppPermissoesDto(true, false, false);
        when(agendaProClient.obterPermissoesWhatsApp(TENANT)).thenReturn(perm);

        assertFalse(service.canCancelViaWhatsApp(TENANT));
    }

    @Test
    @DisplayName("permissões: exceção no client assume tudo permitido (fail-open)")
    void permissoes_excecaoPermiteTudo() {
        when(agendaProClient.obterPermissoesWhatsApp(TENANT)).thenThrow(new RuntimeException("timeout"));

        assertTrue(service.canScheduleViaWhatsApp(TENANT));
        assertTrue(service.canCancelViaWhatsApp(TENANT));
        assertTrue(service.canRescheduleViaWhatsApp(TENANT));
    }

    // ─── resolveRegisteredCustomerName ───────────────────────────────────────

    @Test
    @DisplayName("resolveRegisteredCustomerName: retorna nome quando cliente existe")
    void resolveRegisteredCustomerName_clienteExiste() {
        ClienteDto cliente = new ClienteDto();
        cliente.id = UUID.randomUUID().toString();
        cliente.name = "Ana Lima";
        when(agendaProClient.buscarClientePorIdentificador(TENANT, "+5511999999999")).thenReturn(cliente);

        Optional<String> result = service.resolveRegisteredCustomerName(TENANT, "+5511999999999");
        assertTrue(result.isPresent());
        assertEquals("Ana Lima", result.get());
    }

    @Test
    @DisplayName("resolveRegisteredCustomerName: exceção retorna Optional.empty()")
    void resolveRegisteredCustomerName_excecao_retornaEmpty() {
        when(agendaProClient.buscarClientePorIdentificador(TENANT, "+5511999999999"))
                .thenThrow(new RuntimeException("not found"));

        assertTrue(service.resolveRegisteredCustomerName(TENANT, "+5511999999999").isEmpty());
    }
}
