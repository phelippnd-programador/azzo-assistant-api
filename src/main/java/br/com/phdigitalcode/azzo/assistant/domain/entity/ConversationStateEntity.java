package br.com.phdigitalcode.azzo.assistant.domain.entity;

import java.time.Instant;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "conversation_state")
public class ConversationStateEntity extends PanacheEntityBase {

  @Id
  @Column(name = "id", nullable = false)
  public UUID id;

  @Column(name = "tenant_id", nullable = false)
  public UUID tenantId;

  @Column(name = "user_identifier", nullable = false)
  public String userIdentifier;

  @Column(name = "state_json", nullable = false)
  public String stateJson;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;

  public boolean isPersistent() {
    return id != null;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (updatedAt == null) updatedAt = Instant.now();
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }
}
