package br.com.phdigitalcode.azzo.assistant.application.service;

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
import br.com.phdigitalcode.azzo.assistant.llm.OllamaDateEnricher;
import br.com.phdigitalcode.azzo.assistant.llm.OllamaIntentService;
import br.com.phdigitalcode.azzo.assistant.llm.OllamaSlotExtractor;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageResponse;
import br.com.phdigitalcode.azzo.assistant.model.IntentPrediction;
import br.com.phdigitalcode.azzo.assistant.model.IntentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testes de integração para os fluxos de agendamento do assistente.
 * Cobre os novos comportamentos: fallbacks Ollama (serviço, profissional, período,
 * confirmação), mensagens informais e fluxos de cancelamento/remarcação.
 *
 * LENIENT porque testes de integração frequentemente preparam stubs que não são
 * atingidos em todos os cenários do mesmo setup.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Fluxos de Agendamento — Integração")
class AssistantBookingFlowIntegrationTest {

    // ─── mocks ────────────────────────────────────────────────────────────────

    @Mock OpenNLPIntentClassifier intentClassifier;
    @Mock OllamaIntentService     ollamaIntentService;
    @Mock OllamaDateEnricher      ollamaDateEnricher;
    @Mock OllamaSlotExtractor     ollamaSlotExtractor;
    @Mock ServiceNameFinder       serviceNameFinder;
    @Mock ProfessionalNameFinder  professionalNameFinder;
    @Mock AssistantDomainService  domainService;
    @Mock ConversationStateRepository stateRepository;
    @Mock ContextoTenant          contextoTenant;

    @Spy
    ObjectMapper objectMapper = buildObjectMapper();

    @InjectMocks
    AssistantConversationService service;

    // ─── constantes ────────────────────────────────────────────────────────────

    private static final String USER_ID   = "+5511999990099";
    private static final String USER_NAME = "Ana Beatriz";

    private UUID tenantId;

    // ─── setup ─────────────────────────────────────────────────────────────────

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @BeforeEach
    void setUp() throws Exception {
        tenantId = UUID.randomUUID();
        setField("ttlMinutes",         120L);
        setField("greetingZone",       "America/Sao_Paulo");
        setField("minIntentConfidence", 0.62d);
        setField("ollamaMinConfidence", 0.75d);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = AssistantConversationService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    private void setupNovoUsuario() {
        when(contextoTenant.obterTenantIdOuFalhar()).thenReturn(tenantId);
        doNothing().when(stateRepository).deleteExpired(any());
        when(stateRepository.findActive(eq(tenantId), eq(USER_ID), any()))
                .thenReturn(Optional.empty());
    }

    private void setupUsuarioComEstado(ConversationData data) throws Exception {
        when(contextoTenant.obterTenantIdOuFalhar()).thenReturn(tenantId);
        doNothing().when(stateRepository).deleteExpired(any());
        ConversationStateEntity entity = new ConversationStateEntity();
        entity.tenantId    = tenantId;
        entity.userIdentifier = USER_ID;
        entity.stateJson   = objectMapper.writeValueAsString(data);
        entity.updatedAt   = Instant.now();
        when(stateRepository.findActive(eq(tenantId), eq(USER_ID), any()))
                .thenReturn(Optional.of(entity));
    }

    private ServicoDto servico(String id, String nome) {
        ServicoDto dto = new ServicoDto();
        dto.id   = id;
        dto.name = nome;
        dto.isActive = true;
        return dto;
    }

    private ProfissionalDto profissional(String id, String nome) {
        ProfissionalDto dto = new ProfissionalDto();
        dto.id   = id;
        dto.name = nome;
        dto.isActive = true;
        return dto;
    }

    private AssistantDomainService.UpcomingAppointmentOption opcaoAgendamento(
            UUID appointmentId, String servico, String profissional) {
        AssistantDomainService.UpcomingAppointmentOption opt = new AssistantDomainService.UpcomingAppointmentOption();
        opt.appointmentId    = appointmentId;
        opt.serviceId        = UUID.randomUUID();
        opt.professionalId   = UUID.randomUUID();
        opt.serviceName      = servico;
        opt.professionalName = profissional;
        opt.date             = LocalDate.now().plusDays(5);
        opt.time             = "10:00";
        return opt;
    }

    private ConversationData estadoComServicoProfissionalEData() {
        ConversationData data = new ConversationData();
        data.stage           = ConversationStage.ASK_DATE;
        data.customerName    = USER_NAME;
        data.userIdentifier  = USER_ID;
        data.serviceId       = UUID.randomUUID();
        data.serviceName     = "Manicure";
        data.professionalId  = UUID.randomUUID();
        data.professionalName = "Maria Silva";
        return data;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. Fallbacks Ollama — extração de slots
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Fallback Ollama — Extração de Slots")
    class OllamaSlotFallback {

        @Test
        @DisplayName("ASK_SERVICE: Ollama extrai serviço em linguagem natural ('quero fazer as unhas')")
        void askService_ollamaExtrai_servicoEmLinguagemNatural() throws Exception {
            ConversationData data = new ConversationData();
            data.stage        = ConversationStage.ASK_SERVICE;
            data.customerName = USER_NAME;
            data.userIdentifier = USER_ID;
            setupUsuarioComEstado(data);

            String servicoId   = UUID.randomUUID().toString();
            ServicoDto manicure = servico(servicoId, "Manicure");

            // Extratores determinísticos falham
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(serviceNameFinder.extractFirst(anyString()))
                    .thenReturn(Optional.empty());
            when(domainService.resolveService(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(domainService.listServices(anyString()))
                    .thenReturn(List.of(manicure));

            // Ollama extrai "Manicure"
            when(ollamaSlotExtractor.extractServiceName(anyString(), eq(List.of("Manicure"))))
                    .thenReturn(Optional.of("Manicure"));
            when(domainService.resolveService(anyString(), eq("Manicure")))
                    .thenReturn(Optional.of(manicure));

            // Para avançar para ASK_PROFESSIONAL (listProfessionalsByService)
            when(domainService.listProfessionalsByService(anyString(), any()))
                    .thenReturn(List.of(profissional(UUID.randomUUID().toString(), "Maria Silva")));

            AssistantMessageResponse resp = service.process("quero fazer as unhas", USER_ID, USER_NAME);

            assertNotNull(resp);
            assertEquals(ConversationStage.ASK_PROFESSIONAL.name(), resp.stage,
                    "Após extrair serviço via Ollama, deve avançar para ASK_PROFESSIONAL. Reply: " + resp.reply);
            assertEquals("Manicure", resp.slots.get("serviceName"),
                    "serviceName deve ser 'Manicure'. Slots: " + resp.slots);
        }

        @Test
        @DisplayName("ASK_SERVICE: Ollama também falha → permanece em ASK_SERVICE com prompt de serviços")
        void askService_ollamaTambemFalha_permaneceAskService() throws Exception {
            ConversationData data = new ConversationData();
            data.stage        = ConversationStage.ASK_SERVICE;
            data.customerName = USER_NAME;
            data.userIdentifier = USER_ID;
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(serviceNameFinder.extractFirst(anyString())).thenReturn(Optional.empty());
            when(domainService.resolveService(anyString(), anyString())).thenReturn(Optional.empty());
            when(domainService.listServices(anyString()))
                    .thenReturn(List.of(servico(UUID.randomUUID().toString(), "Corte")));
            when(ollamaSlotExtractor.extractServiceName(anyString(), any()))
                    .thenReturn(Optional.empty());
            when(domainService.formatServicesPrompt(anyString()))
                    .thenReturn("Qual serviço você quer? 💅\n- Corte");

            AssistantMessageResponse resp = service.process("algo confuso", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_SERVICE.name(), resp.stage,
                    "Sem resolução de serviço, deve permanecer em ASK_SERVICE. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("Corte") || resp.reply.contains("serviço"),
                    "Deve listar os serviços disponíveis. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("ASK_PROFESSIONAL: Ollama extrai profissional por nome parcial ('pode ser a Maria')")
        void askProfessional_ollamaExtrai_porNomeParcial() throws Exception {
            ConversationData data = new ConversationData();
            data.stage             = ConversationStage.ASK_PROFESSIONAL;
            data.customerName      = USER_NAME;
            data.userIdentifier    = USER_ID;
            data.serviceId         = UUID.randomUUID();
            data.serviceName       = "Manicure";
            // Lista de opções já populada (segunda interação no ASK_PROFESSIONAL)
            data.professionalOptionIds.add(UUID.randomUUID().toString());
            data.professionalOptionNames.add("Maria Silva");
            data.professionalOptionIds.add(UUID.randomUUID().toString());
            data.professionalOptionNames.add("Ana Costa");
            setupUsuarioComEstado(data);

            String profId = data.professionalOptionIds.get(0);
            ProfissionalDto maria = profissional(profId, "Maria Silva");

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            // Seleção por índice e por nome determinístico falham
            when(professionalNameFinder.extractFirst(anyString())).thenReturn(Optional.empty());
            when(domainService.resolveProfessional(anyString(), anyString(), any()))
                    .thenReturn(Optional.empty());

            // Ollama extrai "Maria Silva"
            when(ollamaSlotExtractor.extractProfessionalName(
                    anyString(), eq(List.of("Maria Silva", "Ana Costa"))))
                    .thenReturn(Optional.of("Maria Silva"));
            when(domainService.resolveProfessional(anyString(), eq("Maria Silva"), any()))
                    .thenReturn(Optional.of(maria));

            AssistantMessageResponse resp = service.process("pode ser a Maria", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_DATE.name(), resp.stage,
                    "Após extrair profissional via Ollama, deve avançar para ASK_DATE. Reply: " + resp.reply);
            assertEquals("Maria Silva", resp.slots.get("professionalName"),
                    "professionalName deve ser 'Maria Silva'. Slots: " + resp.slots);
        }

        @Test
        @DisplayName("ASK_PERIOD: Ollama extrai período de expressão natural ('de manhã cedo')")
        void askPeriod_ollamaExtrai_expressaoNatural() throws Exception {
            ConversationData data = estadoComServicoProfissionalEData();
            data.stage = ConversationStage.ASK_PERIOD;
            data.date  = LocalDate.now().plusDays(5);
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            // TimePeriod.fromText("de manha cedo") NÃO vai reconhecer → cai no Ollama
            when(ollamaSlotExtractor.extractTimePeriod("de manhã cedo"))
                    .thenReturn(Optional.of("MORNING"));
            when(domainService.suggestTimes(anyString(), any(), any(), any(), eq(TimePeriod.MORNING)))
                    .thenReturn(List.of("09:00", "09:30", "10:00"));

            AssistantMessageResponse resp = service.process("de manhã cedo", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_TIME.name(), resp.stage,
                    "Ollama deve extrair MORNING e avançar para ASK_TIME. Reply: " + resp.reply);
            assertEquals("manha", resp.slots.get("preferredPeriod"),
                    "preferredPeriod deve ser 'manha'. Slots: " + resp.slots);
        }

        @Test
        @DisplayName("ASK_PERIOD: Ollama também falha → permanece em ASK_PERIOD")
        void askPeriod_ollamaTambemFalha_permaneceAskPeriod() throws Exception {
            ConversationData data = estadoComServicoProfissionalEData();
            data.stage = ConversationStage.ASK_PERIOD;
            data.date  = LocalDate.now().plusDays(5);
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(ollamaSlotExtractor.extractTimePeriod(anyString()))
                    .thenReturn(Optional.empty());

            AssistantMessageResponse resp = service.process("mais ou menos no meio do dia", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_PERIOD.name(), resp.stage,
                    "Sem reconhecer período, deve permanecer em ASK_PERIOD. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("manhã") || resp.reply.contains("tarde"),
                    "Deve solicitar o período. Reply: " + resp.reply);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. Confirmação — fallback Ollama para linguagem informal
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Confirmação — Fallback Ollama")
    class ConfirmacaoOllama {

        private ConversationData estadoConfirmacao() {
            ConversationData data = new ConversationData();
            data.stage            = ConversationStage.CONFIRMATION;
            data.customerName     = USER_NAME;
            data.userIdentifier   = USER_ID;
            data.serviceId        = UUID.randomUUID();
            data.serviceName      = "Manicure";
            data.professionalId   = UUID.randomUUID();
            data.professionalName = "Maria Silva";
            data.date             = LocalDate.now().plusDays(3);
            data.time             = "10:00";
            data.appointmentId    = UUID.randomUUID();
            return data;
        }

        @Test
        @DisplayName("'pode confirmar!' → determinístico falha → Ollama detecta true → confirma")
        void confirmacao_ollamaDetectaTrue_confirmaAgendamento() throws Exception {
            ConversationData data = estadoConfirmacao();
            UUID appointmentId   = data.appointmentId;
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            // "pode confirmar" normalizado não é "sim" → isAffirmative = false
            // Ollama detecta confirmação positiva
            when(ollamaSlotExtractor.extractConfirmation("pode confirmar!"))
                    .thenReturn(Optional.of(true));
            doNothing().when(domainService).confirmAppointment(anyString(), eq(appointmentId), eq(USER_ID));

            AssistantMessageResponse resp = service.process("pode confirmar!", USER_ID, USER_NAME);

            verify(domainService).confirmAppointment(anyString(), eq(appointmentId), eq(USER_ID));
            assertNotNull(resp.reply);
            assertTrue(resp.reply.contains("confirmado"),
                    "Deve confirmar o agendamento. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("✅"),
                    "Mensagem de confirmação deve ter emoji. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("'deixa pra la' → determinístico falha → Ollama detecta false → cancela")
        void confirmacao_ollamaDetectaFalse_cancelaAgendamento() throws Exception {
            ConversationData data = estadoConfirmacao();
            UUID appointmentId   = data.appointmentId;
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            // "deixa pra la" normalizado não é "nao" → isNegative = false
            when(ollamaSlotExtractor.extractConfirmation("deixa pra la"))
                    .thenReturn(Optional.of(false));
            doNothing().when(domainService).cancelAppointment(anyString(), eq(appointmentId), eq(USER_ID));

            AssistantMessageResponse resp = service.process("deixa pra la", USER_ID, USER_NAME);

            verify(domainService).cancelAppointment(anyString(), eq(appointmentId), eq(USER_ID));
            assertNotNull(resp.reply);
            assertTrue(resp.reply.toLowerCase().contains("cancelei"),
                    "Deve informar cancelamento. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("'talvez' → determinístico falha → Ollama retorna empty → pede SIM ou NÃO")
        void confirmacao_ambiguo_ollamaEmpty_pedeSiOuNao() throws Exception {
            ConversationData data = estadoConfirmacao();
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(ollamaSlotExtractor.extractConfirmation("talvez"))
                    .thenReturn(Optional.empty());

            AssistantMessageResponse resp = service.process("talvez", USER_ID, USER_NAME);

            assertNotNull(resp.reply);
            assertTrue(resp.reply.contains("SIM") || resp.reply.contains("NÃO"),
                    "Deve orientar com SIM ou NÃO. Reply: " + resp.reply);
            assertEquals(ConversationStage.CONFIRMATION.name(), resp.stage,
                    "Deve permanecer em CONFIRMATION. Stage: " + resp.stage);
            verify(domainService, never()).confirmAppointment(anyString(), any(), anyString());
            verify(domainService, never()).cancelAppointment(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("'sim' determinístico → confirma sem chamar Ollama")
        void confirmacao_simDeterministico_naoCholdaOllama() throws Exception {
            ConversationData data = estadoConfirmacao();
            UUID appointmentId   = data.appointmentId;
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            doNothing().when(domainService).confirmAppointment(anyString(), eq(appointmentId), eq(USER_ID));

            service.process("sim", USER_ID, USER_NAME);

            // Ollama NÃO deve ser chamado quando determinístico resolve
            verify(ollamaSlotExtractor, never()).extractConfirmation(anyString());
            verify(domainService).confirmAppointment(anyString(), eq(appointmentId), eq(USER_ID));
        }

        @Test
        @DisplayName("mensagem de confirmação final contém emojis e dados formatados")
        void confirmacao_mensagemFinal_contemEmojisDados() throws Exception {
            ConversationData data = new ConversationData();
            data.stage            = ConversationStage.ASK_TIME;
            data.customerName     = USER_NAME;
            data.userIdentifier   = USER_ID;
            data.serviceId        = UUID.randomUUID();
            data.serviceName      = "Manicure";
            data.professionalId   = UUID.randomUUID();
            data.professionalName = "Maria Silva";
            data.date             = LocalDate.now().plusDays(3);
            data.preferredPeriod  = TimePeriod.MORNING;
            data.availableTimeOptions.add("10:00");
            setupUsuarioComEstado(data);

            UUID appointmentId = UUID.randomUUID();
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(domainService.isSlotAvailable(anyString(), any(), any(), eq("10:00"), any()))
                    .thenReturn(true);
            when(domainService.createPendingAppointment(
                    anyString(), any(), any(), any(), eq("10:00"), eq(USER_ID), eq(USER_NAME)))
                    .thenReturn(appointmentId);

            AssistantMessageResponse resp = service.process("10:00", USER_ID, USER_NAME);

            assertEquals(ConversationStage.CONFIRMATION.name(), resp.stage);
            assertTrue(resp.reply.contains("✂️"), "Deve ter emoji de tesoura. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("Manicure"), "Deve conter nome do serviço. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("Maria Silva"), "Deve conter nome do profissional. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("SIM"), "Deve conter instrução SIM. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("NÃO"), "Deve conter instrução NÃO. Reply: " + resp.reply);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. Fluxo de cancelamento
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Fluxo de Cancelamento")
    class FluxoCancelamento {

        @Test
        @DisplayName("CANCEL com agendamentos ativos → lista opções numeradas")
        void cancel_comAgendamentos_listaOpcoes() throws Exception {
            setupNovoUsuario();
            when(domainService.resolveRegisteredCustomerName(anyString(), eq(USER_ID)))
                    .thenReturn(Optional.of(USER_NAME));
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.CANCEL, 0.95d));
            when(domainService.canCancelViaWhatsApp(anyString())).thenReturn(true);

            UUID apptId = UUID.randomUUID();
            when(domainService.listUpcomingOptionsForUser(anyString(), eq(USER_ID), eq(10)))
                    .thenReturn(List.of(opcaoAgendamento(apptId, "Manicure", "Maria Silva")));

            AssistantMessageResponse resp = service.process("quero cancelar meu horário", USER_ID, null);

            assertEquals(ConversationStage.ASK_CANCEL_APPOINTMENT.name(), resp.stage,
                    "Deve ir para ASK_CANCEL_APPOINTMENT. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("Manicure"),
                    "Deve listar o agendamento. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("1"),
                    "Deve mostrar número de opção. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("ASK_CANCEL_APPOINTMENT + '1' → cancela e retorna mensagem informal")
        void cancel_selecionaPorNumero_cancelaComSucesso() throws Exception {
            UUID apptId = UUID.randomUUID();
            ConversationData data = new ConversationData();
            data.stage = ConversationStage.ASK_CANCEL_APPOINTMENT;
            data.customerName   = USER_NAME;
            data.userIdentifier = USER_ID;
            data.appointmentOptionIds.add(apptId.toString());
            data.appointmentOptionLabels.add("Manicure com Maria Silva em 2026-03-15 as 10:00");
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.CANCEL, 0.9d));
            when(domainService.canCancelViaWhatsApp(anyString())).thenReturn(true);
            doNothing().when(domainService).cancelAppointmentForUser(anyString(), eq(USER_ID), eq(apptId));

            AssistantMessageResponse resp = service.process("1", USER_ID, USER_NAME);

            verify(domainService).cancelAppointmentForUser(anyString(), eq(USER_ID), eq(apptId));
            assertNotNull(resp.reply);
            assertTrue(resp.reply.toLowerCase().contains("cancelado"),
                    "Deve confirmar cancelamento. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("✅"),
                    "Mensagem de cancelamento deve ter emoji. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("CANCEL sem agendamentos ativos → mensagem específica")
        void cancel_semAgendamentos_retornaMensagem() throws Exception {
            setupNovoUsuario();
            when(domainService.resolveRegisteredCustomerName(anyString(), eq(USER_ID)))
                    .thenReturn(Optional.of(USER_NAME));
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.CANCEL, 0.95d));
            when(domainService.canCancelViaWhatsApp(anyString())).thenReturn(true);
            when(domainService.listUpcomingOptionsForUser(anyString(), eq(USER_ID), eq(10)))
                    .thenReturn(List.of());

            AssistantMessageResponse resp = service.process("cancelar", USER_ID, null);

            assertEquals(ConversationStage.START.name(), resp.stage,
                    "Sem agendamentos, deve voltar para START. Stage: " + resp.stage);
            assertTrue(resp.reply.toLowerCase().contains("não encontrei") || resp.reply.toLowerCase().contains("nao encontrei"),
                    "Deve informar que não há agendamentos. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("CANCEL com salão bloqueado → mensagem de bloqueio")
        void cancel_salaoNaoPermite_retornaBloqueio() throws Exception {
            setupNovoUsuario();
            when(domainService.resolveRegisteredCustomerName(anyString(), eq(USER_ID)))
                    .thenReturn(Optional.of(USER_NAME));
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.CANCEL, 0.95d));
            when(domainService.canCancelViaWhatsApp(anyString())).thenReturn(false);

            AssistantMessageResponse resp = service.process("cancelar meu horário", USER_ID, null);

            assertTrue(resp.reply.toLowerCase().contains("não permite") || resp.reply.toLowerCase().contains("nao permite"),
                    "Deve informar bloqueio. Reply: " + resp.reply);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. Fluxo de remarcação
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Fluxo de Remarcação")
    class FluxoRemarcacao {

        @Test
        @DisplayName("RESCHEDULE com agendamentos ativos → lista opções")
        void reschedule_comAgendamentos_listaOpcoes() throws Exception {
            setupNovoUsuario();
            when(domainService.resolveRegisteredCustomerName(anyString(), eq(USER_ID)))
                    .thenReturn(Optional.of(USER_NAME));
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.RESCHEDULE, 0.95d));
            when(domainService.canRescheduleViaWhatsApp(anyString())).thenReturn(true);

            UUID apptId = UUID.randomUUID();
            when(domainService.listUpcomingOptionsForUser(anyString(), eq(USER_ID), eq(10)))
                    .thenReturn(List.of(opcaoAgendamento(apptId, "Corte", "Bruno")));

            AssistantMessageResponse resp = service.process("quero remarcar", USER_ID, null);

            assertEquals(ConversationStage.ASK_RESCHEDULE_APPOINTMENT.name(), resp.stage,
                    "Deve ir para ASK_RESCHEDULE_APPOINTMENT. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("Corte") || resp.reply.contains("Bruno") || resp.reply.contains("1"),
                    "Deve listar os agendamentos. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("ASK_RESCHEDULE_APPOINTMENT + '1' → avança para ASK_DATE com dados do agendamento")
        void reschedule_selecionaPorNumero_avancaParaData() throws Exception {
            UUID apptId   = UUID.randomUUID();
            UUID serviceId = UUID.randomUUID();
            UUID profId   = UUID.randomUUID();

            ConversationData data = new ConversationData();
            data.stage = ConversationStage.ASK_RESCHEDULE_APPOINTMENT;
            data.customerName   = USER_NAME;
            data.userIdentifier = USER_ID;
            data.appointmentOptionIds.add(apptId.toString());
            data.appointmentOptionLabels.add("Corte com Bruno em 2026-03-20 as 14:00");
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.RESCHEDULE, 0.9d));
            when(domainService.canRescheduleViaWhatsApp(anyString())).thenReturn(true);

            AssistantDomainService.UpcomingAppointmentOption opt = new AssistantDomainService.UpcomingAppointmentOption();
            opt.appointmentId    = apptId;
            opt.serviceId        = serviceId;
            opt.professionalId   = profId;
            opt.serviceName      = "Corte";
            opt.professionalName = "Bruno";
            opt.date             = LocalDate.now().plusDays(10);
            opt.time             = "14:00";
            when(domainService.findUpcomingOptionForUser(anyString(), eq(USER_ID), eq(apptId)))
                    .thenReturn(Optional.of(opt));

            AssistantMessageResponse resp = service.process("1", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_DATE.name(), resp.stage,
                    "Deve avançar para ASK_DATE. Reply: " + resp.reply);
            assertEquals("Corte", resp.slots.get("serviceName"),
                    "serviceName deve ser 'Corte'. Slots: " + resp.slots);
            assertEquals("Bruno", resp.slots.get("professionalName"),
                    "professionalName deve ser 'Bruno'. Slots: " + resp.slots);
            assertTrue(resp.reply.contains("📅"),
                    "Mensagem deve ter emoji de calendário. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("RESCHEDULE sem agendamentos → mensagem de ausência")
        void reschedule_semAgendamentos_retornaMensagem() throws Exception {
            setupNovoUsuario();
            when(domainService.resolveRegisteredCustomerName(anyString(), eq(USER_ID)))
                    .thenReturn(Optional.of(USER_NAME));
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.RESCHEDULE, 0.95d));
            when(domainService.canRescheduleViaWhatsApp(anyString())).thenReturn(true);
            when(domainService.listUpcomingOptionsForUser(anyString(), eq(USER_ID), eq(10)))
                    .thenReturn(List.of());

            AssistantMessageResponse resp = service.process("quero remarcar", USER_ID, null);

            assertEquals(ConversationStage.START.name(), resp.stage);
            assertTrue(resp.reply.toLowerCase().contains("não encontrei") || resp.reply.toLowerCase().contains("nao encontrei"),
                    "Deve informar ausência de agendamentos. Reply: " + resp.reply);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. Casos de borda — horários e disponibilidade
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Casos de Borda — Horários e Disponibilidade")
    class CasosBorda {

        @Test
        @DisplayName("ASK_TIME: sem horários disponíveis no período → solicita outro período ou dia")
        void askTime_semHorarios_solicitaOutroPeriodo() throws Exception {
            ConversationData data = estadoComServicoProfissionalEData();
            data.stage          = ConversationStage.ASK_PERIOD;
            data.date           = LocalDate.now().plusDays(5);
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(ollamaSlotExtractor.extractTimePeriod(anyString())).thenReturn(Optional.empty());
            // "tarde" é reconhecido pelo TimePeriod.fromText
            when(domainService.suggestTimes(anyString(), any(), any(), any(), eq(TimePeriod.AFTERNOON)))
                    .thenReturn(List.of());

            AssistantMessageResponse resp = service.process("tarde", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_TIME.name(), resp.stage,
                    "Sem horários, deve ficar em ASK_TIME. Stage: " + resp.stage);
            assertTrue(resp.reply.toLowerCase().contains("horário") || resp.reply.toLowerCase().contains("periodo")
                    || resp.reply.toLowerCase().contains("período") || resp.reply.toLowerCase().contains("vago"),
                    "Deve informar sobre ausência de horários. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("ASK_TIME: horário digitado não está na lista → mostra horários disponíveis")
        void askTime_horarioForaDaLista_mostraDisponiveis() throws Exception {
            ConversationData data = estadoComServicoProfissionalEData();
            data.stage           = ConversationStage.ASK_TIME;
            data.date            = LocalDate.now().plusDays(5);
            data.preferredPeriod = TimePeriod.MORNING;
            data.availableTimeOptions.add("09:00");
            data.availableTimeOptions.add("10:00");
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));

            AssistantMessageResponse resp = service.process("15:00", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_TIME.name(), resp.stage,
                    "Horário inválido deve manter ASK_TIME. Stage: " + resp.stage);
            assertTrue(resp.reply.contains("09:00") || resp.reply.contains("10:00"),
                    "Deve mostrar horários disponíveis. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("Race condition: slot reservado na criação → mostra horários atualizados")
        void raceCondition_slotReservadoNaCriacao_pedeSelecionarNovamente() throws Exception {
            ConversationData data = estadoComServicoProfissionalEData();
            data.stage           = ConversationStage.ASK_TIME;
            data.date            = LocalDate.now().plusDays(5);
            data.preferredPeriod = TimePeriod.MORNING;
            data.availableTimeOptions.add("09:00");
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(domainService.isSlotAvailable(anyString(), any(), any(), eq("09:00"), any()))
                    .thenReturn(true);
            when(domainService.createPendingAppointment(anyString(), any(), any(), any(), eq("09:00"), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Slot já reservado (race condition)"));
            when(domainService.suggestTimes(anyString(), any(), any(), any(), eq(TimePeriod.MORNING)))
                    .thenReturn(List.of("09:30", "10:00"));

            AssistantMessageResponse resp = service.process("1", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_TIME.name(), resp.stage,
                    "Race condition deve manter ASK_TIME. Stage: " + resp.stage);
            assertTrue(resp.reply.toLowerCase().contains("reservado"),
                    "Deve informar que horário foi reservado. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("ASK_DATE: data no passado → mensagem informal com emoji e mantém ASK_DATE")
        void askDate_dataNoPassado_mensagemInformalComEmoji() throws Exception {
            ConversationData data = estadoComServicoProfissionalEData();
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(ollamaDateEnricher.enrich(anyString())).thenReturn(Optional.empty());

            AssistantMessageResponse resp = service.process("01/01/2020", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_DATE.name(), resp.stage,
                    "Data passada deve manter ASK_DATE. Stage: " + resp.stage);
            assertTrue(resp.reply.contains("passou") || resp.reply.contains("passada"),
                    "Deve informar que data passou. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("😅") || resp.reply.contains("Ex:") || resp.reply.contains("amanhã"),
                    "Mensagem deve ser informal com exemplos. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("ASK_DATE: Ollama interpreta 'semana que vem' como data futura → avança para ASK_PERIOD")
        void askDate_ollamaInterprestaSemanQueVem_avancaParaPeriodo() throws Exception {
            ConversationData data = estadoComServicoProfissionalEData();
            setupUsuarioComEstado(data);

            LocalDate proximaSegunda = LocalDate.now().plusDays(7);
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            // Regex não extrai "semana que vem" → cai no Ollama
            when(ollamaDateEnricher.enrich("semana que vem"))
                    .thenReturn(Optional.of(proximaSegunda));

            AssistantMessageResponse resp = service.process("semana que vem", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_PERIOD.name(), resp.stage,
                    "Ollama deve interpretar data futura e avançar. Reply: " + resp.reply);
            assertEquals(proximaSegunda.toString(), resp.slots.get("date"),
                    "date deve ser a próxima semana. Slots: " + resp.slots);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6. Enriquecimento de intent via Ollama
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Enriquecimento de Intent via Ollama")
    class EnriquecimentoIntent {

        @Test
        @DisplayName("OpenNLP inseguro (UNKNOWN 0.4) + Ollama classifica BOOK 0.88 → inicia agendamento")
        void enrichIntent_opennlpInseguro_ollamaClassificaBook() throws Exception {
            setupNovoUsuario();
            when(domainService.resolveRegisteredCustomerName(anyString(), eq(USER_ID)))
                    .thenReturn(Optional.of(USER_NAME));
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.UNKNOWN, 0.4d));
            // Ollama enriquece para BOOK com alta confiança
            when(ollamaIntentService.classify(anyString(), eq("START")))
                    .thenReturn(Optional.of(new IntentPrediction(IntentType.BOOK, 0.88d)));
            when(domainService.canScheduleViaWhatsApp(anyString())).thenReturn(true);
            when(domainService.formatServicesPrompt(anyString()))
                    .thenReturn("Qual serviço você quer? 💅\n- Manicure");

            AssistantMessageResponse resp = service.process("kero agenda hj", USER_ID, null);

            assertEquals(ConversationStage.ASK_SERVICE.name(), resp.stage,
                    "Ollama deve enriquecer para BOOK e iniciar agendamento. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("OpenNLP inseguro no ASK_DATE → Ollama NÃO é chamado (stage excluído)")
        void enrichIntent_stageAskDate_ollamaNaoChamado() throws Exception {
            ConversationData data = estadoComServicoProfissionalEData();
            data.stage = ConversationStage.ASK_DATE;
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.UNKNOWN, 0.3d));
            when(ollamaDateEnricher.enrich(anyString())).thenReturn(Optional.empty());

            service.process("talvez sexta", USER_ID, USER_NAME);

            // OllamaIntentService.classify NÃO deve ser chamado para ASK_DATE
            verify(ollamaIntentService, never()).classify(anyString(), eq("ASK_DATE"));
        }

        @Test
        @DisplayName("Ollama retorna confiança abaixo de 0.75 → mantém resultado OpenNLP")
        void enrichIntent_ollamaBaixaConfianca_mantемOpennlp() throws Exception {
            setupNovoUsuario();
            when(domainService.resolveRegisteredCustomerName(anyString(), eq(USER_ID)))
                    .thenReturn(Optional.of(USER_NAME));
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.UNKNOWN, 0.4d));
            // Ollama retorna confiança muito baixa
            when(ollamaIntentService.classify(anyString(), anyString()))
                    .thenReturn(Optional.of(new IntentPrediction(IntentType.BOOK, 0.60d)));

            AssistantMessageResponse resp = service.process("algo confuso", USER_ID, null);

            // Com UNKNOWN mantido, handleLowConfidenceIntent deve mostrar menu
            assertTrue(resp.reply.contains("Agendar") || resp.reply.contains("Remarcar") || resp.reply.contains("Cancelar"),
                    "Com intent UNKNOWN, deve mostrar menu de opções. Reply: " + resp.reply);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 7. Mensagens informais — verificação de tom e emojis
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Mensagens Informais — Tom e Emojis")
    class MensagensInformais {

        @Test
        @DisplayName("Pedido de nome contém 'nome' e emoji")
        void pedirNome_contemNomeEEmoji() throws Exception {
            setupNovoUsuario();
            when(domainService.resolveRegisteredCustomerName(anyString(), eq(USER_ID)))
                    .thenReturn(Optional.empty());
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(domainService.canScheduleViaWhatsApp(anyString())).thenReturn(true);

            AssistantMessageResponse resp = service.process("quero agendar", USER_ID, null);

            assertTrue(resp.reply.contains("nome"),
                    "Deve pedir nome. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("😊") || resp.reply.contains("!") || resp.reply.contains("Oi"),
                    "Deve ser informal/amigável. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("Cancelamento confirmado contém emoji e linguagem informal")
        void cancelamentoConfirmado_contemEmojiInformal() throws Exception {
            UUID apptId = UUID.randomUUID();
            ConversationData data = new ConversationData();
            data.stage = ConversationStage.CONFIRMATION;
            data.customerName   = USER_NAME;
            data.userIdentifier = USER_ID;
            data.serviceId      = UUID.randomUUID();
            data.professionalId = UUID.randomUUID();
            data.serviceName    = "Corte";
            data.professionalName = "Bruno";
            data.date           = LocalDate.now().plusDays(5);
            data.time           = "14:00";
            data.appointmentId  = apptId;
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            doNothing().when(domainService).cancelAppointment(anyString(), eq(apptId), eq(USER_ID));

            AssistantMessageResponse resp = service.process("nao", USER_ID, USER_NAME);

            assertTrue(resp.reply.contains("👋") || resp.reply.contains("😊") || resp.reply.toLowerCase().contains("quiser"),
                    "Mensagem de cancelamento deve ser informal. Reply: " + resp.reply);
        }

        @Test
        @DisplayName("Menu de desambiguação é informal e numerado")
        void menuDesambiguacao_eInformalENumericado() throws Exception {
            setupNovoUsuario();
            when(domainService.resolveRegisteredCustomerName(anyString(), eq(USER_ID)))
                    .thenReturn(Optional.of(USER_NAME));
            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.UNKNOWN, 0.3d));
            when(ollamaIntentService.classify(anyString(), anyString()))
                    .thenReturn(Optional.empty()); // Ollama também não resolve

            AssistantMessageResponse resp = service.process("qualquer coisa estranha", USER_ID, null);

            String reply = resp.reply;
            // Deve ter menu numerado
            assertTrue(reply.contains("1") && reply.contains("2"),
                    "Menu deve ser numerado. Reply: " + reply);
            assertTrue(reply.contains("Agendar") || reply.contains("Remarcar"),
                    "Menu deve conter opções. Reply: " + reply);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 8. Comandos de correção
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Comandos de Correção")
    class ComandosCorrecao {

        @Test
        @DisplayName("'trocar dia' no ASK_TIME → volta para ASK_DATE com mensagem informal")
        void trocarDia_noAskTime_voltaParaAskDate() throws Exception {
            ConversationData data = estadoComServicoProfissionalEData();
            data.stage           = ConversationStage.ASK_TIME;
            data.date            = LocalDate.now().plusDays(5);
            data.preferredPeriod = TimePeriod.MORNING;
            data.availableTimeOptions.add("09:00");
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));

            AssistantMessageResponse resp = service.process("trocar dia", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_DATE.name(), resp.stage,
                    "Deve voltar para ASK_DATE. Reply: " + resp.reply);
            assertTrue(resp.reply.contains("📅") || resp.reply.contains("dia"),
                    "Deve pedir novo dia com tom informal. Reply: " + resp.reply);
            assertNull(resp.slots.get("date"), "data deve ser resetada");
        }

        @Test
        @DisplayName("'recomecar' no meio do fluxo → reseta ao ASK_SERVICE mantendo nome")
        void recomecar_meioDoFluxo_resetaMantemNome() throws Exception {
            ConversationData data = new ConversationData();
            data.stage            = ConversationStage.ASK_DATE;
            data.customerName     = USER_NAME;
            data.userIdentifier   = USER_ID;
            data.serviceId        = UUID.randomUUID();
            data.serviceName      = "Manicure";
            data.professionalId   = UUID.randomUUID();
            data.professionalName = "Maria";
            setupUsuarioComEstado(data);

            when(intentClassifier.classifyWithConfidence(anyString()))
                    .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
            when(domainService.formatServicesPrompt(anyString()))
                    .thenReturn("Qual serviço você quer? 💅\n- Manicure");

            AssistantMessageResponse resp = service.process("recomecar", USER_ID, USER_NAME);

            assertEquals(ConversationStage.ASK_SERVICE.name(), resp.stage,
                    "Deve ir para ASK_SERVICE. Stage: " + resp.stage);
            assertEquals(USER_NAME, resp.slots.get("customerName"),
                    "Nome do cliente deve ser mantido. Slots: " + resp.slots);
            assertNull(resp.slots.get("serviceName"), "serviceName deve ser resetado");
        }
    }
}
