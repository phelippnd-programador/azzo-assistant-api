package br.com.phdigitalcode.azzo.assistant.llm.pool.usage;

import java.time.Instant;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmUsageHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Registra o histórico de cada chamada e atualiza os contadores agregados da credencial.
 * A atualização dos contadores é atômica (UPDATE ... SET x = x + n) para não perder
 * contabilização com atualizações concorrentes (seção 20).
 */
@ApplicationScoped
public class LlmUsageRecordingService {

  private static final Logger LOG = Logger.getLogger(LlmUsageRecordingService.class);

  @Inject EntityManager em;

  @Transactional
  public void registrar(UsageEvent evento) {
    if (evento == null) return;
    try {
      persistirHistorico(evento);
      atualizarContadoresCredencial(evento);
    } catch (RuntimeException e) {
      // A contabilização nunca deve derrubar o atendimento — apenas registra o problema.
      LOG.warnf("[UsageRecording] Falha ao registrar uso (credential=%s status=%s): %s",
          evento.credentialId, evento.status, e.getMessage());
    }
  }

  private void persistirHistorico(UsageEvent e) {
    LlmUsageHistory h = new LlmUsageHistory();
    h.tenantId = e.tenantId;
    h.conversaId = trunc(e.conversaId, 120);
    h.mensagemId = trunc(e.mensagemId, 120);
    h.providerId = e.providerId;
    h.credentialId = e.credentialId;
    h.modelId = e.modelId;
    if (e.tokens != null) {
      h.tokensEntrada = e.tokens.entrada();
      h.tokensSaida = e.tokens.saida();
      h.tokensTotal = e.tokens.total();
      h.tokensEstimados = e.tokens.estimado();
    }
    h.custoEstimado = e.custoEstimado;
    h.franquiaGratuita = e.franquiaGratuita;
    h.latenciaMs = e.latenciaMs;
    h.status = e.status;
    h.codigoHttp = e.codigoHttp;
    h.tipoErro = trunc(e.tipoErro, 60);
    h.mensagemErroResumida = trunc(e.mensagemErroResumida, 300);
    h.tentativa = e.tentativa;
    h.fallbackUtilizado = e.fallbackUtilizado;
    h.dataRequisicao = e.dataRequisicao != null ? e.dataRequisicao : Instant.now();
    h.dataResposta = e.dataResposta;
    h.persist();
  }

  private void atualizarContadoresCredencial(UsageEvent e) {
    if (e.credentialId == null) return;
    int entrada = e.tokens != null ? e.tokens.entrada() : 0;
    int saida = e.tokens != null ? e.tokens.saida() : 0;
    int errInc = e.contaComoErro() ? 1 : 0;

    em.createNativeQuery("""
            UPDATE llm_credential SET
              total_requisicoes    = total_requisicoes + 1,
              total_tokens_entrada = total_tokens_entrada + :entrada,
              total_tokens_saida   = total_tokens_saida + :saida,
              total_erros          = total_erros + :errInc,
              ultimo_uso           = now(),
              data_atualizacao     = now()
            WHERE id = :cred
            """)
        .setParameter("entrada", entrada)
        .setParameter("saida", saida)
        .setParameter("errInc", errInc)
        .setParameter("cred", e.credentialId)
        .executeUpdate();
  }

  private String trunc(String v, int max) {
    if (v == null) return null;
    return v.length() <= max ? v : v.substring(0, max);
  }
}
