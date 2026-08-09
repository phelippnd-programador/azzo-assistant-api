package br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity;

import java.time.Instant;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Auditoria de operações administrativas do pool. Nunca contém a chave (seção 21). */
@Entity
@Table(name = "llm_admin_audit")
public class LlmAdminAudit extends PanacheEntityBase {

  @Id
  @Column(name = "id", nullable = false)
  public UUID id;

  @Column(name = "usuario", length = 160)
  public String usuario;

  @Column(name = "operacao", nullable = false, length = 60)
  public String operacao;

  @Column(name = "provider_id")
  public UUID providerId;

  @Column(name = "credential_id")
  public UUID credentialId;

  @Column(name = "identificacao", length = 200)
  public String identificacao;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "detalhes", nullable = false, columnDefinition = "jsonb")
  public String detalhes = "{}";

  @Column(name = "data_operacao", nullable = false)
  public Instant dataOperacao;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (dataOperacao == null) dataOperacao = Instant.now();
    if (detalhes == null || detalhes.isBlank()) detalhes = "{}";
  }
}
