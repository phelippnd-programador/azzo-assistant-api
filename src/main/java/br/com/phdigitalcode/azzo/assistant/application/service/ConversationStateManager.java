package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import br.com.phdigitalcode.azzo.assistant.domain.repository.ConversationStateRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Responsável exclusivamente pelas operações de banco de dados do estado de conversa.
 * Cada método abre e fecha sua própria transação rapidamente (< 50ms), sem segurar
 * conexão durante chamadas lentas ao LLM.
 */
@ApplicationScoped
public class ConversationStateManager {

    private static final Logger LOG = Logger.getLogger(ConversationStateManager.class);

    @Inject
    ConversationStateRepository stateRepository;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Limpa estados expirados e retorna (ou cria) a entidade ativa para o usuário.
     * Transação curta: apenas operações de leitura/criação no DB.
     */
    @Transactional
    public ConversationStateEntity loadOrCreate(UUID tenantId, String userIdentifier, Instant threshold) {
        stateRepository.deleteExpired(threshold);
        return stateRepository.findActive(tenantId, userIdentifier, threshold)
                .orElseGet(() -> createNew(tenantId, userIdentifier));
    }

    /**
     * Persiste o estado atualizado após o LLM ter respondido.
     * Transação curta: apenas um UPDATE/INSERT.
     */
    @Transactional
    public void save(ConversationStateEntity entity, String stateJson) {
        entity.stateJson = stateJson;
        entity.updatedAt = Instant.now();
        stateRepository.save(entity);
    }

    /**
     * Remove o estado (conversa concluída ou reiniciada).
     */
    @Transactional
    public void delete(ConversationStateEntity entity) {
        if (entity.isPersistent()) {
            stateRepository.delete(entity);
        }
    }

    /**
     * Pré-semeia um contexto de confirmação de presença para o cliente.
     * Chamado pelo ReminderScheduler imediatamente após enviar o lembrete via WhatsApp.
     *
     * Quando o cliente responder, o assistente encontrará este estado e processará
     * a resposta como confirmação/cancelamento do agendamento existente — sem iniciar
     * um novo fluxo de booking.
     *
     * Se já existir uma conversa ativa para este usuário, ela é substituída, pois
     * a confirmação de presença tem prioridade.
     */
    @Transactional
    public void seedReminderContext(UUID tenantId, String userIdentifier,
            UUID appointmentId, String customerName) {
        // Remove qualquer estado anterior para não haver conflito
        stateRepository.findActive(tenantId, userIdentifier, Instant.EPOCH)
                .ifPresent(stateRepository::delete);

        ConversationData data = new ConversationData();
        data.stage = ConversationStage.AWAITING_APPOINTMENT_CONFIRMATION;
        data.appointmentId = appointmentId;
        data.customerName = customerName;
        data.userIdentifier = userIdentifier;

        ConversationStateEntity entity = new ConversationStateEntity();
        entity.tenantId = tenantId;
        entity.userIdentifier = userIdentifier;
        entity.stateJson = toJson(data);
        entity.updatedAt = Instant.now();
        stateRepository.save(entity);

        LOG.infof("[StateManager] Contexto de confirmação de presença criado: tenant=%s user=%s appointment=%s",
                tenantId, userIdentifier, appointmentId);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    public String toJson(ConversationData data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar estado da conversa", e);
        }
    }

    public ConversationData parseState(String stateJson) {
        if (stateJson == null || stateJson.isBlank()) return new ConversationData();
        try {
            return objectMapper.readValue(stateJson, ConversationData.class);
        } catch (Exception e) {
            return new ConversationData();
        }
    }

    private ConversationStateEntity createNew(UUID tenantId, String userIdentifier) {
        ConversationStateEntity entity = new ConversationStateEntity();
        entity.tenantId = tenantId;
        entity.userIdentifier = userIdentifier;
        entity.stateJson = toJson(new ConversationData());
        entity.updatedAt = Instant.now();
        return entity;
    }
}
