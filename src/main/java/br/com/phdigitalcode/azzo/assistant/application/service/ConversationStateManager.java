package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import br.com.phdigitalcode.azzo.assistant.domain.repository.ConversationStateRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Responsável exclusivamente pelas operações de banco de dados do estado de conversa.
 * Cada método abre e fecha sua própria transação rapidamente (< 50ms), sem segurar
 * conexão durante chamadas lentas ao LLM.
 */
@ApplicationScoped
public class ConversationStateManager {

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
