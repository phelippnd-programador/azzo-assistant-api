package br.com.phdigitalcode.azzo.assistant.application.service;

import br.com.phdigitalcode.azzo.assistant.classifier.OpenNLPIntentClassifier;
import br.com.phdigitalcode.azzo.assistant.dialogue.ChatMessage;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import br.com.phdigitalcode.azzo.assistant.dialogue.TimePeriod;
import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import br.com.phdigitalcode.azzo.assistant.domain.repository.ConversationStateRepository;
import br.com.phdigitalcode.azzo.assistant.extractor.ProfessionalNameFinder;
import br.com.phdigitalcode.azzo.assistant.extractor.ServiceNameFinder;
import br.com.phdigitalcode.azzo.assistant.infrastructure.tenant.ContextoTenant;
import br.com.phdigitalcode.azzo.assistant.llm.AgentSystemPromptBuilder;
import br.com.phdigitalcode.azzo.assistant.llm.LlmBookingAgent;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageResponse;
import br.com.phdigitalcode.azzo.assistant.model.IntentPrediction;
import br.com.phdigitalcode.azzo.assistant.model.IntentType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * Cobre o fluxo orientado a LLM (assistant.agent.enabled=true), especificamente
 * os bugs corrigidos: reconhecimento de horário coloquial ("17h"), prioridade
 * horário > período, não repetir pergunta sobre dado já conhecido e rejeição de
 * respostas contraditórias antes de chegarem ao cliente/histórico.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AssistantConversationService — fluxo agente (LLM pool)")
class AssistantConversationServiceAgentFlowUnitTest {

    @Mock OpenNLPIntentClassifier intentClassifier;
    @Mock ServiceNameFinder serviceNameFinder;
    @Mock ProfessionalNameFinder professionalNameFinder;
    @Mock AssistantDomainService domainService;
    @Mock ConversationStateRepository stateRepository;
    @Mock ConversationStateManager stateManager;
    @Mock ContextoTenant contextoTenant;
    @Mock AgentSystemPromptBuilder agentSystemPromptBuilder;
    @Mock LlmBookingAgent llmBookingAgent;
    @Mock ConversationLockManager lockManager;

    @Spy
    ObjectMapper objectMapper = buildObjectMapper();

    @InjectMocks
    AssistantConversationService service;

    private static final String USER_ID = "+5511999990001";
    private static final String USER_NAME = "Phelipp";
    private UUID tenantId;
    private UUID professionalId;
    private UUID serviceId;

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @BeforeEach
    void setUp() throws Exception {
        tenantId = UUID.randomUUID();
        professionalId = UUID.randomUUID();
        serviceId = UUID.randomUUID();
        setPrivateField("ttlMinutes", 120L);
        setPrivateField("greetingZone", "America/Sao_Paulo");
        setPrivateField("minIntentConfidence", 0.62d);
        setPrivateField("agentEnabled", true);
        setPrivateField("llmMaxInputChars", 1000);
        setPrivateField("maxHistoryMessages", 80);
        setPrivateField("keepHistoryMessages", 60);
        setPrivateField("maxHistoryChars", 3000);
        lenient().when(stateManager.toJson(any(ConversationData.class))).thenReturn("{}");
        lenient().doNothing().when(stateManager).save(any(ConversationStateEntity.class), anyString());
        lenient().doNothing().when(stateManager).delete(any(ConversationStateEntity.class));
        lenient().when(contextoTenant.obterTenantIdOuFalhar()).thenReturn(tenantId);
        lenient().when(stateRepository.deleteExpired(any())).thenReturn(0L);
        lenient().when(agentSystemPromptBuilder.build(anyString())).thenReturn("SYSTEM_PROMPT_STUB");
        // ConversationLockManager só serializa por chave tenant+telefone; em teste unitário
        // basta executar a ação recebida diretamente, sem lock real (ver Fix 1).
        lenient().when(lockManager.withLock(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> action = invocation.getArgument(1);
            return action.get();
        });
    }

    private void setPrivateField(String fieldName, Object value) throws Exception {
        Field field = AssistantConversationService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    private ConversationStateEntity entityComEstado(ConversationData data) throws Exception {
        ConversationStateEntity entity = new ConversationStateEntity();
        entity.tenantId = tenantId;
        entity.userIdentifier = USER_ID;
        entity.stateJson = objectMapper.writeValueAsString(data);
        entity.updatedAt = Instant.now();
        return entity;
    }

    private void setupUsuarioComEstado(ConversationData data) throws Exception {
        when(stateRepository.findActive(eq(tenantId), eq(USER_ID), any()))
                .thenReturn(Optional.of(entityComEstado(data)));
    }

    // ─── Sintoma 1: saudação não deve inventar "Confirma?" ───────────────────────

    @Test
    @DisplayName("'Oi' em conversa nova responde deterministicamente pedindo o próximo dado, sem chamar o LLM")
    void oi_semDadosOperacionais_naoChamaLlmEPedeProximoCampo() throws Exception {
        ConversationData estadoNovo = new ConversationData();
        estadoNovo.customerName = USER_NAME;
        estadoNovo.stage = ConversationStage.START;
        setupUsuarioComEstado(estadoNovo);

        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.GREETING, 0.97d));
        when(domainService.formatServicesPrompt(any())).thenReturn("Qual serviço você deseja?");

        AssistantMessageResponse response = service.process("Oi", USER_ID, USER_NAME);

        assertEquals("Qual serviço você deseja?", response.reply);
        assertEquals(ConversationStage.ASK_SERVICE, response.stage);
        verify(llmBookingAgent, never()).chat(anyString(), anyList(), anyString(), any(), any());
    }

    // ─── Sintoma 2: horário coloquial + prioridade sobre período ────────────────

    @Test
    @DisplayName("'Às 17h' com profissional/data já conhecidos: reconhece horário, deriva período e nunca perde o profissional/data")
    void horarioColoquial_reconheceEDerivaPerioco_semReperguntar() throws Exception {
        ConversationData estado = new ConversationData();
        estado.customerName = USER_NAME;
        estado.serviceId = serviceId;
        estado.serviceName = "Corte Masculino";
        estado.professionalId = professionalId;
        estado.professionalName = "Riane";
        estado.date = LocalDate.now().plusDays(1);
        estado.stage = ConversationStage.ASK_TIME;
        setupUsuarioComEstado(estado);

        // Não estuba intentClassifier: com horário já reconhecido no texto, o
        // atalho determinístico curto-circuita antes de classificar intenção.

        // Simula o bug real de produção: o LLM emite o alias "riane" (nome, não o
        // alias interno tipo "P1"), que portanto NÃO resolve — resolveProfessionalId
        // retorna Optional.empty() por padrão do Mockito.
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        when(llmBookingAgent.chat(anyString(), anyList(), messageCaptor.capture(), any(), any()))
                .thenReturn(new LlmBookingAgent.AgentResult(
                        "Vou verificar se 17h está disponível com a Riane 😊",
                        List.of(new LlmBookingAgent.AgentAction("CONSULTAR_HORARIOS",
                                java.util.Map.of("prof", "riane", "date", estado.date.toString()))),
                        "POOL"));
        when(domainService.suggestTimes(eq(tenantId.toString()), eq(professionalId), eq(estado.date), eq(serviceId), any()))
                .thenReturn(List.of("17:00", "17:30"));

        AssistantMessageResponse response = service.process("Às 17h", USER_ID, USER_NAME);

        // Horário reconhecido e período derivado dele (17h -> tarde)
        assertEquals("17:00", response.slots.get("time"));
        assertEquals(TimePeriod.AFTERNOON.label(), response.slots.get("preferredPeriod"));

        // O prompt enviado ao LLM não pode mais pedir período/horário — o horário já é conhecido
        String sentMessage = messageCaptor.getValue();
        assertFalse(sentMessage.toLowerCase().contains("falta agora: periodo"),
                "Não deveria pedir período com horário já resolvido. Mensagem: " + sentMessage);
        assertTrue(sentMessage.toLowerCase().contains("consultar disponibilidade"),
                "Deveria sinalizar consulta de disponibilidade. Mensagem: " + sentMessage);

        // executeConsultarHorarios não pode ter perdido profissional/data já conhecidos
        assertFalse(response.reply.toLowerCase().contains("nao consegui identificar")
                        && response.reply.toLowerCase().contains("profissional"),
                "Não deveria contradizer dados já conhecidos. Reply: " + response.reply);
    }

    // ─── Sintoma 3: resposta contraditória nunca chega ao cliente/histórico ─────

    @Test
    @DisplayName("Resposta da LLM que contradiz profissional/data já conhecidos é descartada e substituída")
    void respostaContraditoria_eDescartadaESubstituida() throws Exception {
        ConversationData estado = new ConversationData();
        estado.customerName = USER_NAME;
        estado.serviceId = serviceId;
        estado.serviceName = "Corte Masculino";
        estado.professionalId = professionalId;
        estado.professionalName = "Riane";
        estado.date = LocalDate.now().plusDays(1);
        estado.time = "17:00";
        estado.preferredPeriod = TimePeriod.AFTERNOON;
        estado.stage = ConversationStage.ASK_TIME;
        setupUsuarioComEstado(estado);

        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.UNKNOWN, 0.1d));
        when(llmBookingAgent.chat(anyString(), anyList(), anyString(), any(), any()))
                .thenReturn(new LlmBookingAgent.AgentResult(
                        "Não consegui identificar o profissional ou a data. Tente novamente.",
                        List.of(),
                        "POOL"));

        AssistantMessageResponse response = service.process("confirma pra mim", USER_ID, USER_NAME);

        assertFalse(response.reply.toLowerCase().contains("nao consegui identificar"),
                "Resposta contraditória não deveria chegar ao cliente. Reply: " + response.reply);
    }

    // ─── Fix 2: conflito de horário (race condition) ao criar o agendamento ─────

    @Test
    @DisplayName("Confirmação 'sim' com horário que virou indisponível entre a checagem e a criação (IllegalStateException do domainService) reoferece horários em vez de propagar o erro ao cliente")
    void confirmacaoComConflitoDeHorario_reofereceHorariosEmVezDePropagarErro() throws Exception {
        ConversationData estado = new ConversationData();
        estado.customerName = USER_NAME;
        estado.serviceId = serviceId;
        estado.serviceName = "Corte Masculino";
        estado.professionalId = professionalId;
        estado.professionalName = "Riane";
        estado.date = LocalDate.now().plusDays(1);
        estado.time = "17:00";
        estado.preferredPeriod = TimePeriod.AFTERNOON;
        estado.stage = ConversationStage.CONFIRMATION;
        setupUsuarioComEstado(estado);

        // isSlotAvailable() ainda vê o horário como livre (checagem "otimista"), mas
        // outro cliente reserva entre essa checagem e o INSERT — o service de domínio
        // relança isso como IllegalStateException.
        when(domainService.isSlotAvailable(eq(tenantId.toString()), eq(professionalId), eq(estado.date), eq("17:00"), eq(serviceId)))
                .thenReturn(true);
        when(domainService.createPendingAppointment(eq(tenantId.toString()), eq(serviceId), eq(professionalId),
                eq(estado.date), eq("17:00"), eq(USER_ID), eq(USER_NAME)))
                .thenThrow(new IllegalStateException("Horario indisponivel para criacao do agendamento"));
        when(domainService.suggestTimes(eq(tenantId.toString()), eq(professionalId), eq(estado.date), eq(serviceId), any()))
                .thenReturn(List.of("18:00", "18:30"));

        AssistantMessageResponse response = service.process("sim", USER_ID, USER_NAME);

        assertNotNull(response);
        assertEquals(ConversationStage.ASK_TIME, response.stage,
                "Deve voltar a pedir horário em vez de deixar a exceção do banco propagar. Reply: " + response.reply);
        assertTrue(response.reply.contains("18:00") && response.reply.contains("18:30"),
                "Deve reoferecer os novos horários disponíveis em vez de uma mensagem de erro genérica. Reply: " + response.reply);
        // O conflito é tratado deterministicamente — não deveria custar uma chamada ao LLM.
        verify(llmBookingAgent, never()).chat(anyString(), anyList(), anyString(), any(), any());
    }
}
