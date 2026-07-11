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
 * Credencial (API key) de um provedor. Um provedor pode ter várias credenciais.
 * A chave é gravada SEMPRE criptografada em {@link #apiKeyCriptografada}.
 */
@Entity
@Table(name = "llm_credential")
public class LlmCredential extends PanacheEntityBase {

  @Id
  @Column(name = "id", nullable = false)
  public UUID id;

  @Column(name = "provider_id", nullable = false)
  public UUID providerId;

  @Column(name = "nome_identificacao", nullable = false, length = 120)
  public String nomeIdentificacao;

  /** API key criptografada (AES/GCM). Nunca exposta em claro fora do momento da chamada. */
  @Column(name = "api_key_criptografada", nullable = false)
  public String apiKeyCriptografada;

  @Column(name = "organizacao", length = 200)
  public String organizacao;

  @Column(name = "projeto", length = 200)
  public String projeto;

  @Column(name = "ativo", nullable = false)
  public boolean ativo = true;

  /** Exclusão lógica — preserva o histórico de utilização. */
  @Column(name = "removida", nullable = false)
  public boolean removida = false;

  @Column(name = "peso", nullable = false)
  public int peso = 100;

  @Column(name = "prioridade", nullable = false)
  public int prioridade = 100;

  @Column(name = "limite_requisicoes_minuto")
  public Integer limiteRequisicoesMinuto;

  @Column(name = "limite_requisicoes_dia")
  public Integer limiteRequisicoesDia;

  @Column(name = "limite_tokens_minuto")
  public Long limiteTokensMinuto;

  @Column(name = "limite_tokens_dia")
  public Long limiteTokensDia;

  @Column(name = "limite_tokens_mes")
  public Long limiteTokensMes;

  @Column(name = "limite_custo_mensal")
  public BigDecimal limiteCustoMensal;

  @Column(name = "total_tokens_entrada", nullable = false)
  public long totalTokensEntrada = 0;

  @Column(name = "total_tokens_saida", nullable = false)
  public long totalTokensSaida = 0;

  @Column(name = "total_requisicoes", nullable = false)
  public long totalRequisicoes = 0;

  @Column(name = "total_erros", nullable = false)
  public long totalErros = 0;

  @Column(name = "ultimo_uso")
  public Instant ultimoUso;

  /** Bloqueio temporário (ex.: após 429). Enquanto no futuro, a credencial não é elegível. */
  @Column(name = "bloqueada_ate")
  public Instant bloqueadaAte;

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

  /** Elegível para uso agora: ativa, não removida e sem bloqueio vigente. */
  public boolean elegivel(Instant agora) {
    if (!ativo || removida) return false;
    return bloqueadaAte == null || bloqueadaAte.isBefore(agora);
  }
}
