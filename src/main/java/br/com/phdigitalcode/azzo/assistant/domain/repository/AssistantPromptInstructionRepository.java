package br.com.phdigitalcode.azzo.assistant.domain.repository;

import java.util.Optional;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.domain.entity.AssistantPromptInstructionEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class AssistantPromptInstructionRepository
    implements PanacheRepositoryBase<AssistantPromptInstructionEntity, UUID> {

  public Optional<AssistantPromptInstructionEntity> findActiveByKey(String instructionKey) {
    return find("instructionKey = ?1 and active = true", instructionKey).firstResultOptional();
  }
}
