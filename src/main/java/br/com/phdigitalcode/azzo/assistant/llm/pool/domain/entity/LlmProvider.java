package br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity;

import java.time.Instant;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.LlmProviderType;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Provedor de LLM cadastrado (ex.: Groq, OpenRouter, Cerebras, Google AI Studio). */
@Entity
@Table(name = "llm_provider")
public class LlmProvider extends PanacheEntityBase {

  @Id
  @Column(name = "id", nullable = false)
  public UUID id;

  @Column(name = "nome", nullable = false, length = 120)
  public String nome;

  @Enumerated(EnumType.STRING)
  @Column(name = "tipo", nullable = false, length = 40)
  public LlmProviderType tipo;

  @Column(name = "url_base", length = 500)
  public String urlBase;

  @Column(name = "ativo", nullable = false)
  public boolean ativo = true;

  @Column(name = "prioridade", nullable = false)
  public int prioridade = 100;

  @Column(name = "ordem_fallback", nullable = false)
  public int ordemFallback = 100;

  /** Configuração do adaptador (endpoint, headers, esquema de auth, parâmetros extras) em JSON. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "config_adaptador", nullable = false, columnDefinition = "jsonb")
  public String configAdaptador = "{}";

  @Column(name = "timeout_conexao_ms", nullable = false)
  public int timeoutConexaoMs = 5000;

  @Column(name = "timeout_resposta_ms", nullable = false)
  public int timeoutRespostaMs = 30000;

  @Column(name = "quantidade_maxima_tentativas", nullable = false)
  public int quantidadeMaximaTentativas = 2;

  @Column(name = "suporta_streaming", nullable = false)
  public boolean suportaStreaming = false;

  @Column(name = "suporta_tool_calling", nullable = false)
  public boolean suportaToolCalling = false;

  @Column(name = "suporta_json_mode", nullable = false)
  public boolean suportaJsonMode = false;

  @Column(name = "ultima_sincronizacao_modelos")
  public Instant ultimaSincronizacaoModelos;

  @Column(name = "data_criacao", nullable = false)
  public Instant dataCriacao;

  @Column(name = "data_atualizacao", nullable = false)
  public Instant dataAtualizacao;

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (id == null) id = UUID.randomUUID();
    if (configAdaptador == null || configAdaptador.isBlank()) configAdaptador = "{}";
    if (dataCriacao == null) dataCriacao = now;
    if (dataAtualizacao == null) dataAtualizacao = now;
  }

  @PreUpdate
  void preUpdate() {
    dataAtualizacao = Instant.now();
  }
}
