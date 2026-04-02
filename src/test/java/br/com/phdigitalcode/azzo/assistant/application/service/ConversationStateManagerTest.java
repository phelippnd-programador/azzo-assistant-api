package br.com.phdigitalcode.azzo.assistant.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import br.com.phdigitalcode.azzo.assistant.domain.repository.ConversationStateRepository;

@ExtendWith(MockitoExtension.class)
class ConversationStateManagerTest {

    @Mock
    ConversationStateRepository stateRepository;

    @Spy
    ObjectMapper objectMapper = buildObjectMapper();

    @InjectMocks
    ConversationStateManager stateManager;

    @Test
    void devePersistirContextoDeReativacaoComEstadoAguardandoResposta() {
        UUID tenantId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        UUID professionalId = UUID.randomUUID();
        String userIdentifier = "5511999990001";

        when(stateRepository.findActive(eq(tenantId), eq(userIdentifier), any()))
                .thenReturn(Optional.empty());

        stateManager.seedReactivationContext(
                tenantId,
                userIdentifier,
                cycleId,
                "Phelipp Nascimento",
                "ASK_TIME",
                serviceId.toString(),
                "Corte",
                professionalId.toString(),
                "Maria",
                "2026-04-02",
                "10:00",
                "Posso seguir com esse horario?");

        ArgumentCaptor<ConversationStateEntity> captor = ArgumentCaptor.forClass(ConversationStateEntity.class);
        verify(stateRepository).save(captor.capture());
        ConversationStateEntity saved = captor.getValue();

        assertEquals(tenantId, saved.tenantId);
        assertEquals(userIdentifier, saved.userIdentifier);

        ConversationData parsed = stateManager.parseState(saved.stateJson);
        assertEquals(ConversationStage.AWAITING_REACTIVATION_REPLY, parsed.stage);
        assertEquals(cycleId, parsed.reactivationCycleId);
        assertEquals(ConversationStage.ASK_TIME, parsed.reactivationResumeStage);
        assertEquals(serviceId, parsed.serviceId);
        assertEquals("Corte", parsed.serviceName);
        assertEquals(professionalId, parsed.professionalId);
        assertEquals("Maria", parsed.professionalName);
        assertEquals(LocalDate.parse("2026-04-02"), parsed.date);
        assertEquals("10:00", parsed.time);
        assertEquals("Posso seguir com esse horario?", parsed.reactivationLastPrompt);
    }

    @Test
    void deveUsarAskServiceComoFallbackQuandoResumeStageForInvalido() {
        UUID tenantId = UUID.randomUUID();
        String userIdentifier = "5511999990001";

        when(stateRepository.findActive(eq(tenantId), eq(userIdentifier), any()))
                .thenReturn(Optional.empty());

        stateManager.seedReactivationContext(
                tenantId,
                userIdentifier,
                UUID.randomUUID(),
                "Phelipp Nascimento",
                "STAGE_INEXISTENTE",
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        ArgumentCaptor<ConversationStateEntity> captor = ArgumentCaptor.forClass(ConversationStateEntity.class);
        verify(stateRepository).save(captor.capture());
        ConversationData parsed = stateManager.parseState(captor.getValue().stateJson);

        assertEquals(ConversationStage.AWAITING_REACTIVATION_REPLY, parsed.stage);
        assertEquals(ConversationStage.ASK_SERVICE, parsed.reactivationResumeStage);
        assertNull(parsed.serviceId);
        assertNull(parsed.professionalId);
    }

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
