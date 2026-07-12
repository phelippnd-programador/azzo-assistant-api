package br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * Bucket de consumo de uma credencial por janela de tempo (MINUTO/HORA/DIA/MES).
 * Atualizado via upsert atômico (INSERT ... ON CONFLICT DO UPDATE), o que mantém a
 * contagem correta mesmo com múltiplas instâncias da aplicação — sem contadores em memória.
 */
@Entity
@Table(name = "llm_consumption_bucket")
public class LlmConsumptionBucket extends PanacheEntityBase {

  @Id
  @Column(name = "id", nullable = false)
  public UUID id;

  @Column(name = "credential_id", nullable = false)
  public UUID credentialId;

  /** MINUTO | HORA | DIA | MES. */
  @Column(name = "janela", nullable = false, length = 10)
  public String janela;

  @Column(name = "janela_inicio", nullable = false)
  public Instant janelaInicio;

  @Column(name = "requisicoes", nullable = false)
  public int requisicoes = 0;

  @Column(name = "tokens", nullable = false)
  public long tokens = 0;

  @Column(name = "custo", nullable = false)
  public BigDecimal custo = BigDecimal.ZERO;

  @Column(name = "data_criacao", nullable = false)
  public Instant dataCriacao;

  @Column(name = "data_atualizacao", nullable = false)
  public Instant dataAtualizacao;

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (id == null) id = UUID.randomUUID();
    if (dataCriacao == null) dataCriacao = now;
    if (dataAtualizacao == null) dataAtualizacao = now;
  }

  @PreUpdate
  void preUpdate() {
    dataAtualizacao = Instant.now();
  }
}
