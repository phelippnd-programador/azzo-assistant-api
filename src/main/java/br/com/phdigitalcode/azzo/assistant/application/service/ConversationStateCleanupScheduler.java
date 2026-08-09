package br.com.phdigitalcode.azzo.assistant.application.service;

import java.time.Duration;
import java.time.Instant;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import br.com.phdigitalcode.azzo.assistant.domain.repository.ConversationStateRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Remove periodicamente as conversas expiradas (contexto de LLM, guardado em
 * ConversationStateEntity/stateJson) de forma independente do tráfego de
 * mensagens. Antes esse DELETE global rodava a cada mensagem recebida (dentro
 * de ConversationStateManager.loadOrCreate), pagando a escrita na latência do
 * WhatsApp de qualquer tenant e parando de rodar se não houvesse tráfego.
 *
 * Afeta SOMENTE o contexto/histórico enviado ao LLM. As mensagens do WhatsApp
 * exibidas em painel ao usuário humano vivem em outro serviço (azzo-agenda-pro,
 * tabelas chat_messages/whatsapp_message_log) com sua própria retenção — esta
 * rotina não tem acesso a esse banco e não as afeta.
 */
@ApplicationScoped
public class ConversationStateCleanupScheduler {

  private static final Logger LOG = Logger.getLogger(ConversationStateCleanupScheduler.class);

  @Inject
  ConversationStateRepository stateRepository;

  @ConfigProperty(name = "assistant.conversation.ttl-minutes", defaultValue = "480")
  long ttlMinutes;

  @Scheduled(
      every = "${assistant.conversation.cleanup-interval:30m}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  @Transactional
  void cleanupExpiredConversations() {
    Instant threshold = Instant.now().minus(Duration.ofMinutes(ttlMinutes));
    long removed = stateRepository.deleteExpired(threshold);
    if (removed > 0) {
      LOG.infof("[ConversationCleanup] %d conversa(s) expirada(s) removida(s) (threshold=%s)", removed, threshold);
    } else {
      LOG.debugf("[ConversationCleanup] Nenhuma conversa expirada (threshold=%s)", threshold);
    }
  }
}
