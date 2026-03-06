package br.com.phdigitalcode.azzo.assistant.application.service;

import br.com.phdigitalcode.azzo.assistant.classifier.OpenNLPIntentClassifier;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import br.com.phdigitalcode.azzo.assistant.domain.repository.ConversationStateRepository;
import br.com.phdigitalcode.azzo.assistant.extractor.ProfessionalNameFinder;
import br.com.phdigitalcode.azzo.assistant.extractor.ServiceNameFinder;
import br.com.phdigitalcode.azzo.assistant.infrastructure.tenant.ContextoTenant;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssistantConversationService")
class AssistantConversationServiceTest {

    @Mock
    OpenNLPIntentClassifier intentClassifier;

    @Mock
    ServiceNameFinder serviceNameFinder;

    @Mock
    ProfessionalNameFinder professionalNameFinder;

    @Mock
    AssistantDomainService domainService;

    @Mock
    ConversationStateRepository stateRepository;

    @Mock
    ContextoTenant contextoTenant;

    @Spy
    ObjectMapper objectMapper = buildObjectMapper();

    @InjectMocks
    AssistantConversationService service;

    private static final String USER_ID = "+5511999990001";
    private static final String USER_NAME = "Phelipp Nascimento";
    private UUID tenantId;

    // ─── setup ────────────────────────────────────────────────────────────────

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @BeforeEach
    void setUp() throws Exception {
        tenantId = UUID.randomUUID();
        setPrivateField("ttlMinutes", 120L);
        setPrivateField("greetingZone", "America/Sao_Paulo");
        setPrivateField("minIntentConfidence", 0.62d);
    }

    private void setPrivateField(String fieldName, Object value) throws Exception {
        Field field = AssistantConversationService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    /** Cria entity transiente (sem id) com JSON de ConversationData serializado. */
    private ConversationStateEntity entityComEstado(ConversationData data) throws Exception {
        ConversationStateEntity entity = new ConversationStateEntity();
        entity.tenantId = tenantId;
        entity.userIdentifier = USER_ID;
        entity.stateJson = objectMapper.writeValueAsString(data);
        entity.updatedAt = Instant.now();
        // id = null → isPersistent() = false
        return entity;
    }

    /** Configura stateRepository para novo usuário (sem estado persistido). */
    private void setupNovoUsuario() {
        when(contextoTenant.obterTenantIdOuFalhar()).thenReturn(tenantId);
        doNothing().when(stateRepository).deleteExpired(any());
        when(stateRepository.findActive(eq(tenantId), eq(USER_ID), any()))
                .thenReturn(Optional.empty());
//        doNothing().when(stateRepository).persist(any());
    }

    /** Configura stateRepository para usuário com estado pré-existente. */
    private void setupUsuarioComEstado(ConversationData data) throws Exception {
        when(contextoTenant.obterTenantIdOuFalhar()).thenReturn(tenantId);
        doNothing().when(stateRepository).deleteExpired(any());
        when(stateRepository.findActive(eq(tenantId), eq(USER_ID), any()))
                .thenReturn(Optional.of(entityComEstado(data)));
//        doNothing().when(stateRepository).persist(any());
    }

    // ─── testes de guarda básicos ─────────────────────────────────────────────

    @Test
    @DisplayName("process: mensagem nula lança IllegalArgumentException")
    void process_mensagemNula_lancaExcecao() {
        assertThrows(IllegalArgumentException.class,
                () -> service.process(null, USER_ID, USER_NAME));
    }

    @Test
    @DisplayName("process: mensagem em branco lança IllegalArgumentException")
    void process_mensagemEmBranco_lancaExcecao() {
        assertThrows(IllegalArgumentException.class,
                () -> service.process("   ", USER_ID, USER_NAME));
    }

    // ─── fluxo de novo agendamento ────────────────────────────────────────────

    @Test
    @DisplayName("process: novo usuário sem nome registrado → solicita nome (ASK_NAME)")
    void process_novoUsuario_pedePrimeiroONome() throws Exception {
        setupNovoUsuario();
        when(domainService.resolveRegisteredCustomerName(any(), eq(USER_ID)))
                .thenReturn(Optional.empty());
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
        when(domainService.canScheduleViaWhatsApp(any())).thenReturn(true);

        // Mensagem que não é um nome (tem muitos dígitos)
        AssistantMessageResponse response = service.process("quero agendar", USER_ID, null);

        assertNotNull(response);
        assertNotNull(response.reply);
        assertTrue(response.reply.toLowerCase().contains("nome"),
                "Deve pedir o nome do cliente. Reply: " + response.reply);
        assertEquals(ConversationStage.ASK_NAME.name(), response.stage);
    }

    @Test
    @DisplayName("process: nome do usuário fornecido no header → avança para ASK_SERVICE")
    void process_nomeNoHeader_avancaParaAskService() throws Exception {
        setupNovoUsuario();
        // Não precisa chamar resolveRegisteredCustomerName pois userName não é nulo
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
        when(domainService.canScheduleViaWhatsApp(any())).thenReturn(true);
        when(domainService.formatServicesPrompt(any()))
                .thenReturn("Me diga qual servico voce quer agendar. Opcoes: Corte, Manicure");

        // Envia a mensagem com o nome já presente no header
        AssistantMessageResponse response = service.process("quero agendar", USER_ID, USER_NAME);

        assertNotNull(response);
        assertEquals(ConversationStage.ASK_SERVICE.name(), response.stage);
        assertEquals(USER_NAME, response.slots.get("customerName"));
    }

    @Test
    @DisplayName("process: saudação determinística 'oi' → responde com boas-vindas e pede nome")
    void process_saudacaoOi_retornaBoasVindas() throws Exception {
        setupNovoUsuario();
        when(domainService.resolveRegisteredCustomerName(any(), any()))
                .thenReturn(Optional.empty());

        AssistantMessageResponse response = service.process("oi", USER_ID, null);

        assertNotNull(response.reply);
        // Deve ser uma saudação + pedido de nome
        assertTrue(
                response.reply.toLowerCase().contains("bom dia")
                        || response.reply.toLowerCase().contains("boa tarde")
                        || response.reply.toLowerCase().contains("boa noite"),
                "Deve conter saudação temporal. Reply: " + response.reply);
    }

    // ─── validação de data ────────────────────────────────────────────────────

    @Test
    @DisplayName("process: data no passado → retorna erro e mantém etapa ASK_DATE")
    void process_dataNoPassado_solicitaNovaData() throws Exception {
        // Monta estado com serviço e profissional já selecionados, aguardando data
        ConversationData estadoPreexistente = new ConversationData();
        estadoPreexistente.stage = ConversationStage.ASK_DATE;
        estadoPreexistente.customerName = USER_NAME;
        estadoPreexistente.serviceId = UUID.randomUUID();
        estadoPreexistente.professionalId = UUID.randomUUID();
        estadoPreexistente.serviceName = "Corte";
        estadoPreexistente.professionalName = "Maria";

        setupUsuarioComEstado(estadoPreexistente);
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));

        // Data claramente no passado
        String dataPassada = "01/01/2020";
        AssistantMessageResponse response = service.process(dataPassada, USER_ID, USER_NAME);

        assertNotNull(response.reply);
        assertTrue(
                response.reply.toLowerCase().contains("data ja passou") || response.reply.toLowerCase().contains("passou"),
                "Deve informar que a data já passou. Reply: " + response.reply);
        assertEquals(ConversationStage.ASK_DATE.name(), response.stage);
    }

    @Test
    @DisplayName("process: data futura válida → avança para ASK_PERIOD")
    void process_dataFutura_avancaParaAskPeriod() throws Exception {
        ConversationData estadoPreexistente = new ConversationData();
        estadoPreexistente.stage = ConversationStage.ASK_DATE;
        estadoPreexistente.customerName = USER_NAME;
        estadoPreexistente.serviceId = UUID.randomUUID();
        estadoPreexistente.professionalId = UUID.randomUUID();
        estadoPreexistente.serviceName = "Corte";
        estadoPreexistente.professionalName = "Maria";

        setupUsuarioComEstado(estadoPreexistente);
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));

        LocalDate dataFutura = LocalDate.now().plusDays(10);
        String input = dataFutura.getDayOfMonth() + "/" + dataFutura.getMonthValue() + "/" + dataFutura.getYear();
        AssistantMessageResponse response = service.process(input, USER_ID, USER_NAME);

        assertNotNull(response);
        assertEquals(ConversationStage.ASK_PERIOD.name(), response.stage);
        assertTrue(response.reply.toLowerCase().contains("manha") || response.reply.toLowerCase().contains("tarde"),
                "Deve pedir o período. Reply: " + response.reply);
    }

    // ─── confirmação de agendamento ───────────────────────────────────────────

    @Test
    @DisplayName("process: 'sim' em CONFIRMATION → confirma agendamento e reseta estado")
    void process_simNaConfirmacao_confirmaAgendamento() throws Exception {
        UUID appointmentId = UUID.randomUUID();

        ConversationData estadoConfirmacao = new ConversationData();
        estadoConfirmacao.stage = ConversationStage.CONFIRMATION;
        estadoConfirmacao.customerName = USER_NAME;
        estadoConfirmacao.userIdentifier = USER_ID;
        estadoConfirmacao.serviceId = UUID.randomUUID();
        estadoConfirmacao.professionalId = UUID.randomUUID();
        estadoConfirmacao.serviceName = "Corte";
        estadoConfirmacao.professionalName = "Maria";
        estadoConfirmacao.date = LocalDate.now().plusDays(5);
        estadoConfirmacao.time = "10:00";
        estadoConfirmacao.appointmentId = appointmentId;

        setupUsuarioComEstado(estadoConfirmacao);
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
        doNothing().when(domainService).confirmAppointment(any(), eq(appointmentId), eq(USER_ID));

        AssistantMessageResponse response = service.process("sim", USER_ID, USER_NAME);

        verify(domainService).confirmAppointment(any(), eq(appointmentId), eq(USER_ID));
        assertNotNull(response.reply);
        assertTrue(response.reply.toLowerCase().contains("confirmado"),
                "Deve confirmar o agendamento. Reply: " + response.reply);
    }

    @Test
    @DisplayName("process: 'nao' em CONFIRMATION → cancela agendamento pendente")
    void process_naoNaConfirmacao_cancelaAgendamento() throws Exception {
        UUID appointmentId = UUID.randomUUID();

        ConversationData estadoConfirmacao = new ConversationData();
        estadoConfirmacao.stage = ConversationStage.CONFIRMATION;
        estadoConfirmacao.customerName = USER_NAME;
        estadoConfirmacao.userIdentifier = USER_ID;
        estadoConfirmacao.serviceId = UUID.randomUUID();
        estadoConfirmacao.professionalId = UUID.randomUUID();
        estadoConfirmacao.serviceName = "Corte";
        estadoConfirmacao.professionalName = "Maria";
        estadoConfirmacao.date = LocalDate.now().plusDays(5);
        estadoConfirmacao.time = "10:00";
        estadoConfirmacao.appointmentId = appointmentId;

        setupUsuarioComEstado(estadoConfirmacao);
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
        doNothing().when(domainService).cancelAppointment(any(), eq(appointmentId), eq(USER_ID));

        AssistantMessageResponse response = service.process("nao", USER_ID, USER_NAME);

        verify(domainService).cancelAppointment(any(), eq(appointmentId), eq(USER_ID));
        assertNotNull(response.reply);
        assertTrue(response.reply.toLowerCase().contains("nao confirmei") || response.reply.toLowerCase().contains("cancelei"),
                "Deve informar que não confirmou. Reply: " + response.reply);
    }

    // ─── resiliência de estado ────────────────────────────────────────────────

    @Test
    @DisplayName("process: JSON corrompido no estado → reinicia conversa sem erro")
    void process_jsonCorrompido_reiniciaConversa() throws Exception {
        when(contextoTenant.obterTenantIdOuFalhar()).thenReturn(tenantId);
        doNothing().when(stateRepository).deleteExpired(any());

        // Entidade com JSON inválido
        ConversationStateEntity entityCorrompida = new ConversationStateEntity();
        entityCorrompida.tenantId = tenantId;
        entityCorrompida.userIdentifier = USER_ID;
        entityCorrompida.stateJson = "{ INVALID JSON @@@ }";
        entityCorrompida.updatedAt = Instant.now();

        when(stateRepository.findActive(eq(tenantId), eq(USER_ID), any()))
                .thenReturn(Optional.of(entityCorrompida));
//        doNothing().when(stateRepository).persist(any());

        when(domainService.resolveRegisteredCustomerName(any(), any()))
                .thenReturn(Optional.empty());
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
        when(domainService.canScheduleViaWhatsApp(any())).thenReturn(true);

        // Não deve lançar exceção — deve reiniciar silenciosamente
        AssistantMessageResponse response = assertDoesNotThrow(
                () -> service.process("quero agendar", USER_ID, null));

        assertNotNull(response);
        // Conversa reinicia do zero → deve pedir nome
        assertEquals(ConversationStage.ASK_NAME.name(), response.stage);
    }

    // ─── extração de nome ─────────────────────────────────────────────────────

    @Test
    @DisplayName("process: texto com 3+ dígitos não é aceito como nome")
    void process_textoComDigitos_naoAceitaComoNome() throws Exception {
        setupNovoUsuario();
        when(domainService.resolveRegisteredCustomerName(any(), any()))
                .thenReturn(Optional.empty());
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
        when(domainService.canScheduleViaWhatsApp(any())).thenReturn(true);

        // Texto com muitos dígitos — parece número de telefone, não nome
        AssistantMessageResponse response = service.process("11999990001", USER_ID, null);

        assertNotNull(response);
        // Deve continuar pedindo o nome
        assertTrue(
                ConversationStage.ASK_NAME.name().equals(response.stage)
                        || response.reply.toLowerCase().contains("nome"),
                "Não deve aceitar número como nome. Stage: " + response.stage + ", Reply: " + response.reply);
    }

    @Test
    @DisplayName("process: nome válido (apenas letras) é aceito como customerName")
    void process_nomeValido_aceitoEAvancaParaServico() throws Exception {
        setupNovoUsuario();
        when(domainService.resolveRegisteredCustomerName(any(), any()))
                .thenReturn(Optional.empty());
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
        when(domainService.canScheduleViaWhatsApp(any())).thenReturn(true);
        when(domainService.formatServicesPrompt(any()))
                .thenReturn("Me diga qual servico voce quer agendar. Opcoes: Corte");

        AssistantMessageResponse response = service.process("Ana Lima", USER_ID, null);

        assertNotNull(response);
        // Nome foi aceito → avança para ASK_SERVICE
        assertEquals(ConversationStage.ASK_SERVICE.name(), response.stage,
                "Nome válido deve avançar para ASK_SERVICE. Reply: " + response.reply);
        assertEquals("Ana Lima", response.slots.get("customerName"));
    }

    // ─── permissões WhatsApp ──────────────────────────────────────────────────

    @Test
    @DisplayName("process: salão sem permissão de agendamento → retorna mensagem de bloqueio")
    void process_salaoSemPermissaoAgendamento_retornaBloqueio() throws Exception {
        setupNovoUsuario();
        when(domainService.resolveRegisteredCustomerName(any(), any()))
                .thenReturn(Optional.of(USER_NAME));
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.9d));
        when(domainService.canScheduleViaWhatsApp(any())).thenReturn(false);

        AssistantMessageResponse response = service.process("quero agendar", USER_ID, null);

        assertNotNull(response.reply);
        assertTrue(response.reply.toLowerCase().contains("nao permite"),
                "Deve informar que agendamento não é permitido. Reply: " + response.reply);
    }

    // ─── resposta de listagem ─────────────────────────────────────────────────

    @Test
    @DisplayName("process: intenção LIST com alta confiança → lista agendamentos")
    void process_intentList_listaAgendamentos() throws Exception {
        setupNovoUsuario();
        when(domainService.resolveRegisteredCustomerName(any(), any()))
                .thenReturn(Optional.of(USER_NAME));
        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.LIST, 0.95d));
        when(domainService.listUpcomingForUser(any(), eq(USER_ID)))
                .thenReturn("Seus proximos agendamentos:\n- Corte com Maria em 2026-03-15 as 10:00");

        AssistantMessageResponse response = service.process("meus agendamentos", USER_ID, null);

        verify(domainService).listUpcomingForUser(any(), eq(USER_ID));
        assertNotNull(response.reply);
        assertTrue(response.reply.toLowerCase().contains("agendamentos"),
                "Deve listar agendamentos. Reply: " + response.reply);
    }
}
