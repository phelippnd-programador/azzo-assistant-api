package br.com.phdigitalcode.azzo.assistant.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ConversationStateRepository implements PanacheRepositoryBase<ConversationStateEntity, UUID> {

  public Optional<ConversationStateEntity> findActive(UUID tenantId, String userIdentifier, Instant threshold) {
    return find(
            "tenantId = ?1 and userIdentifier = ?2 and updatedAt >= ?3",
            tenantId,
            userIdentifier,
            threshold)
        .firstResultOptional();
  }

  @Transactional
  public long deleteExpired(Instant threshold) {
    return delete("updatedAt < ?1", threshold);
  }
}
