package br.com.phdigitalcode.azzo.assistant.domain.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ConversationStateRepository implements PanacheRepositoryBase<ConversationStateEntity, UUID> {

  @Inject EntityManager entityManager;

  public Optional<ConversationStateEntity> findActive(UUID tenantId, String userIdentifier, Instant threshold) {
    return find(
            "tenantId = ?1 and userIdentifier = ?2 and updatedAt >= ?3",
            tenantId,
            userIdentifier,
            threshold)
        .firstResultOptional();
  }

  @Transactional
  public ConversationStateEntity save(ConversationStateEntity entity) {
    if (entity == null) {
      throw new IllegalArgumentException("entity obrigatoria");
    }
    if (entity.isPersistent()) {
      return entityManager.merge(entity);
    }
    entityManager.persist(entity);
    return entity;
  }

  @Transactional
  public long deleteExpired(Instant threshold) {
    return delete("updatedAt < ?1", threshold);
  }
}
