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

/** Modelo disponível em um provedor, com custo e capacidades configuráveis. */
@Entity
@Table(name = "llm_model")
public class LlmModel extends PanacheEntityBase {

  @Id
  @Column(name = "id", nullable = false)
  public UUID id;

  @Column(name = "provider_id", nullable = false)
  public UUID providerId;

  @Column(name = "nome_modelo", nullable = false, length = 200)
  public String nomeModelo;

  @Column(name = "nome_exibicao", length = 200)
  public String nomeExibicao;

  @Column(name = "ativo", nullable = false)
  public boolean ativo = true;

  /** Marcado quando a sincronização não encontrou mais o modelo — sem apagar a config. */
  @Column(name = "descontinuado", nullable = false)
  public boolean descontinuado = false;

  @Column(name = "prioridade", nullable = false)
  public int prioridade = 100;

  @Column(name = "context_window")
  public Integer contextWindow;

  @Column(name = "max_output_tokens")
  public Integer maxOutputTokens;

  @Column(name = "custo_input_por_milhao", nullable = false)
  public BigDecimal custoInputPorMilhao = BigDecimal.ZERO;

  @Column(name = "custo_output_por_milhao", nullable = false)
  public BigDecimal custoOutputPorMilhao = BigDecimal.ZERO;

  @Column(name = "custo_fixo_por_chamada", nullable = false)
  public BigDecimal custoFixoPorChamada = BigDecimal.ZERO;

  @Column(name = "moeda", nullable = false, length = 3)
  public String moeda = "USD";

  @Column(name = "gratuito", nullable = false)
  public boolean gratuito = false;

  @Column(name = "indicado_para_atendimento", nullable = false)
  public boolean indicadoParaAtendimento = false;

  @Column(name = "suporta_streaming", nullable = false)
  public boolean suportaStreaming = false;

  @Column(name = "suporta_tool_calling", nullable = false)
  public boolean suportaToolCalling = false;

  @Column(name = "suporta_json_mode", nullable = false)
  public boolean suportaJsonMode = false;

  @Column(name = "limite_requisicoes_minuto")
  public Integer limiteRequisicoesMinuto;

  @Column(name = "limite_requisicoes_dia")
  public Integer limiteRequisicoesDia;

  @Column(name = "limite_tokens_minuto")
  public Long limiteTokensMinuto;

  @Column(name = "limite_tokens_dia")
  public Long limiteTokensDia;

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
