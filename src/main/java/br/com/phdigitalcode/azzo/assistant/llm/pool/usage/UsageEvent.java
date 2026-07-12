package br.com.phdigitalcode.azzo.assistant.llm.pool.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.UsageStatus;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.TokenUsage;

/**
 * Dados de uma chamada a ser registrada no histórico. Não carrega prompt nem resposta
 * completos — apenas identificadores seguros, métricas e resultado (seções 5.4 e 24).
 */
public class UsageEvent {
  public UUID tenantId;
  public String conversaId;
  public String mensagemId;
  public UUID providerId;
  public UUID credentialId;
  public UUID modelId;
  public TokenUsage tokens = TokenUsage.vazio();
  public BigDecimal custoEstimado = BigDecimal.ZERO;
  public boolean franquiaGratuita = false;
  public Integer latenciaMs;
  public UsageStatus status = UsageStatus.SUCESSO;
  public Integer codigoHttp;
  public String tipoErro;
  public String mensagemErroResumida;
  public int tentativa = 1;
  public boolean fallbackUtilizado = false;
  public Instant dataRequisicao;
  public Instant dataResposta;

  public boolean sucesso() {
    return status == UsageStatus.SUCESSO;
  }

  public boolean contaComoErro() {
    return status == UsageStatus.ERRO || status == UsageStatus.TIMEOUT || status == UsageStatus.RATE_LIMITED;
  }
}
