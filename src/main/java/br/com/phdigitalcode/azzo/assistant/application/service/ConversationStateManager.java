package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.Instant;
import java.time.LocalDate;
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

@ApplicationScoped
public class ConversationStateManager {

    private static final Logger LOG = Logger.getLogger(ConversationStateManager.class);

    @Inject
    ConversationStateRepository stateRepository;

    @Inject
    ObjectMapper objectMapper;

    @Transactional
    public ConversationStateEntity loadOrCreate(UUID tenantId, String userIdentifier, Instant threshold) {
        stateRepository.deleteExpired(threshold);
        return stateRepository.findActive(tenantId, userIdentifier, threshold)
                .orElseGet(() -> createNew(tenantId, userIdentifier));
    }

    @Transactional
    public void save(ConversationStateEntity entity, String stateJson) {
        entity.stateJson = stateJson;
        entity.updatedAt = Instant.now();
        stateRepository.save(entity);
    }

    @Transactional
    public void delete(ConversationStateEntity entity) {
        if (entity.isPersistent()) {
            stateRepository.delete(entity);
        }
    }

    @Transactional
    public void seedReminderContext(UUID tenantId, String userIdentifier, UUID appointmentId, String customerName) {
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

        LOG.infof("[StateManager] Reminder context created: tenant=%s user=%s appointment=%s",
                tenantId, userIdentifier, appointmentId);
    }

    @Transactional
    public void seedReactivationContext(
            UUID tenantId,
            String userIdentifier,
            UUID cycleId,
            String customerName,
            String resumeStage,
            String serviceId,
            String serviceName,
            String professionalId,
            String professionalName,
            String date,
            String time,
            String assistantLastPrompt) {
        stateRepository.findActive(tenantId, userIdentifier, Instant.EPOCH)
                .ifPresent(stateRepository::delete);

        ConversationData data = new ConversationData();
        data.stage = ConversationStage.AWAITING_REACTIVATION_REPLY;
        data.reactivationCycleId = cycleId;
        data.customerName = customerName;
        data.userIdentifier = userIdentifier;
        data.reactivationResumeStage = parseStage(resumeStage);
        data.serviceId = parseUuid(serviceId);
        data.serviceName = serviceName;
        data.professionalId = parseUuid(professionalId);
        data.professionalName = professionalName;
        data.date = parseLocalDate(date);
        data.time = time;
        data.reactivationLastPrompt = assistantLastPrompt;

        ConversationStateEntity entity = new ConversationStateEntity();
        entity.tenantId = tenantId;
        entity.userIdentifier = userIdentifier;
        entity.stateJson = toJson(data);
        entity.updatedAt = Instant.now();
        stateRepository.save(entity);

        LOG.infof("[StateManager] Reactivation context created: tenant=%s user=%s cycle=%s stage=%s",
                tenantId, userIdentifier, cycleId, data.reactivationResumeStage);
    }

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

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ConversationStage parseStage(String value) {
        if (value == null || value.isBlank()) return ConversationStage.ASK_SERVICE;
        try {
            return ConversationStage.valueOf(value.trim());
        } catch (Exception ignored) {
            return ConversationStage.ASK_SERVICE;
        }
    }
}
