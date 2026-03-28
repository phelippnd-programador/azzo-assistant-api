package br.com.phdigitalcode.azzo.assistant.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.phdigitalcode.azzo.assistant.classifier.OpenNLPIntentClassifier;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import br.com.phdigitalcode.azzo.assistant.extractor.ProfessionalNameFinder;
import br.com.phdigitalcode.azzo.assistant.extractor.ServiceNameFinder;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ServicoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.tenant.ContextoTenant;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageResponse;
import br.com.phdigitalcode.azzo.assistant.model.IntentPrediction;
import br.com.phdigitalcode.azzo.assistant.model.IntentType;

@ExtendWith(MockitoExtension.class)
@DisplayName("AssistantConversationReactivation")
class AssistantConversationReactivationUnitTest {

    @Mock OpenNLPIntentClassifier intentClassifier;
    @Mock ServiceNameFinder serviceNameFinder;
    @Mock ProfessionalNameFinder professionalNameFinder;
    @Mock AssistantDomainService domainService;
    @Mock ConversationStateManager stateManager;
    @Mock ContextoTenant contextoTenant;

    @InjectMocks
    AssistantConversationService service;

    private static final String USER_ID = "+5511999990001";
    private static final String USER_NAME = "Phelipp Nascimento";
    private UUID tenantId;

    @BeforeEach
    void setUp() throws Exception {
        tenantId = UUID.randomUUID();
        setPrivateField("ttlMinutes", 120L);
        setPrivateField("greetingZone", "America/Sao_Paulo");
        setPrivateField("minIntentConfidence", 0.62d);
        lenient().when(stateManager.toJson(any(ConversationData.class))).thenReturn("{}");
        lenient().doNothing().when(stateManager).save(any(ConversationStateEntity.class), anyString());
        lenient().doNothing().when(stateManager).delete(any(ConversationStateEntity.class));
        when(contextoTenant.obterTenantIdOuFalhar()).thenReturn(tenantId);
    }

    @Test
    void deveRetomarDoPassoSalvoQuandoClienteAceitarReativacao() {
        ConversationData data = new ConversationData();
        data.stage = ConversationStage.AWAITING_REACTIVATION_REPLY;
        data.customerName = USER_NAME;
        data.userIdentifier = USER_ID;
        data.reactivationResumeStage = ConversationStage.ASK_SERVICE;

        mockCurrentState(data);
        when(domainService.formatServicesPrompt(anyString()))
                .thenReturn("Me diga qual servico voce quer agendar.");

        AssistantMessageResponse response = service.process("sim", USER_ID, USER_NAME);

        assertNotNull(response);
        assertEquals(ConversationStage.ASK_SERVICE.name(), response.stage);
        assertTrue(response.reply.toLowerCase().contains("retomar"));
        assertTrue(response.reply.toLowerCase().contains("servico"));
    }

    @Test
    void deveEncerrarContextoQuandoClienteRecusarReativacao() {
        ConversationData data = new ConversationData();
        data.stage = ConversationStage.AWAITING_REACTIVATION_REPLY;
        data.customerName = USER_NAME;
        data.userIdentifier = USER_ID;
        data.reactivationResumeStage = ConversationStage.ASK_TIME;

        mockCurrentState(data);

        AssistantMessageResponse response = service.process("nao quero", USER_ID, USER_NAME);

        assertNotNull(response);
        assertEquals(ConversationStage.START.name(), response.stage);
        assertTrue(response.reply.toLowerCase().contains("tudo certo"));
    }

    @Test
    void deveSinalizarLeadDeAgendamentoMesmoQuandoAindaEstiverPedindoNome() {
        ConversationData data = new ConversationData();
        data.stage = ConversationStage.START;

        mockCurrentState(data);

        ServicoDto corte = new ServicoDto();
        corte.id = UUID.randomUUID().toString();
        corte.name = "Corte";

        when(intentClassifier.classifyWithConfidence(anyString()))
                .thenReturn(new IntentPrediction(IntentType.BOOK, 0.95d));
        when(serviceNameFinder.extractFirst(anyString())).thenReturn(Optional.of("corte"));
        when(domainService.resolveService(anyString(), eq("corte"))).thenReturn(Optional.of(corte));
        when(domainService.canScheduleViaWhatsApp(anyString())).thenReturn(true);
        when(domainService.resolveRegisteredCustomerName(anyString(), anyString())).thenReturn(Optional.empty());

        AssistantMessageResponse response = service.process(
                "quero agendar para hoje um corte de cabelo",
                USER_ID,
                null);

        assertEquals(ConversationStage.ASK_NAME.name(), response.stage);
        assertEquals(Boolean.TRUE, response.slots.get("bookingLeadDetected"));
        assertEquals(corte.id, response.slots.get("bookingLeadServiceId"));
        assertEquals("Corte", response.slots.get("bookingLeadServiceName"));
        assertEquals(LocalDate.now().toString(), response.slots.get("bookingLeadDate"));
    }

    private void mockCurrentState(ConversationData data) {
        ConversationStateEntity entity = new ConversationStateEntity();
        entity.tenantId = tenantId;
        entity.userIdentifier = USER_ID;
        entity.stateJson = "{}";
        entity.updatedAt = Instant.now();

        when(stateManager.loadOrCreate(eq(tenantId), eq(USER_ID), any())).thenReturn(entity);
        when(stateManager.parseState(eq("{}"))).thenReturn(data);
    }

    private void setPrivateField(String fieldName, Object value) throws Exception {
        Field field = AssistantConversationService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }
}
