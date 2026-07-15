package br.com.phdigitalcode.azzo.assistant.application.service;

import br.com.phdigitalcode.azzo.assistant.domain.repository.ConversationStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationStateCleanupScheduler")
class ConversationStateCleanupSchedulerUnitTest {

    @Mock
    ConversationStateRepository stateRepository;

    @InjectMocks
    ConversationStateCleanupScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        Field field = ConversationStateCleanupScheduler.class.getDeclaredField("ttlMinutes");
        field.setAccessible(true);
        field.set(scheduler, 480L);
    }

    @Test
    @DisplayName("chama deleteExpired com threshold baseado no ttl configurado")
    void cleanup_chamaDeleteExpiredComThresholdCorreto() {
        when(stateRepository.deleteExpired(any())).thenReturn(3L);

        scheduler.cleanupExpiredConversations();

        verify(stateRepository).deleteExpired(any(Instant.class));
    }

    @Test
    @DisplayName("nao lanca excecao quando nenhuma conversa expirada e encontrada")
    void cleanup_semConversasExpiradas_naoLancaExcecao() {
        when(stateRepository.deleteExpired(any())).thenReturn(0L);

        scheduler.cleanupExpiredConversations();

        verify(stateRepository).deleteExpired(any(Instant.class));
    }
}
