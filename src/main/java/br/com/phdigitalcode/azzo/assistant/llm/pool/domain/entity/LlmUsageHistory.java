package br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.UsageStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Histórico de uma chamada a um provedor. NÃO armazena prompt/resposta completos
 * nem dados pessoais — apenas identificadores, métricas e resultado.
 */
@Entity
@Table(name = "llm_usage_history")
public class LlmUsageHistory extends PanacheEntityBase {

  @Id
  @Column(name = "id", nullable = false)
  public UUID id;

  @Column(name = "tenant_id")
  public UUID tenantId;

  @Column(name = "conversa_id", length = 120)
  public String conversaId;

  @Column(name = "mensagem_id", length = 120)
  public String mensagemId;

  @Column(name = "provider_id")
  public UUID providerId;

  @Column(name = "credential_id")
  public UUID credentialId;

  @Column(name = "model_id")
  public UUID modelId;

  @Column(name = "tokens_entrada", nullable = false)
  public int tokensEntrada = 0;

  @Column(name = "tokens_saida", nullable = false)
  public int tokensSaida = 0;

  @Column(name = "tokens_total", nullable = false)
  public int tokensTotal = 0;

  /** true quando os tokens foram estimados (provedor não retornou usage). */
  @Column(name = "tokens_estimados", nullable = false)
  public boolean tokensEstimados = false;

  @Column(name = "custo_estimado", nullable = false)
  public BigDecimal custoEstimado = BigDecimal.ZERO;

  /** true quando a chamada consumiu franquia gratuita (custo zero identificado). */
  @Column(name = "franquia_gratuita", nullable = false)
  public boolean franquiaGratuita = false;

  @Column(name = "latencia_ms")
  public Integer latenciaMs;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  public UsageStatus status;

  @Column(name = "codigo_http")
  public Integer codigoHttp;

  @Column(name = "tipo_erro", length = 60)
  public String tipoErro;

  @Column(name = "mensagem_erro_resumida", length = 300)
  public String mensagemErroResumida;

  @Column(name = "tentativa", nullable = false)
  public int tentativa = 1;

  @Column(name = "fallback_utilizado", nullable = false)
  public boolean fallbackUtilizado = false;

  @Column(name = "data_requisicao", nullable = false)
  public Instant dataRequisicao;

  @Column(name = "data_resposta")
  public Instant dataResposta;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (dataRequisicao == null) dataRequisicao = Instant.now();
  }
}
