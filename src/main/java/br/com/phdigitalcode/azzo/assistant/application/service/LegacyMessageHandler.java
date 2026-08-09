package br.com.phdigitalcode.azzo.assistant.application.service;

import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fluxo determinístico legado: máquina de estados em Java, sem depender do LLM para
 * conduzir a conversa. A implementação vive em {@link #handleMessage}
 * (em {@link AbstractMessageHandler}), pois também é o fallback usado pelo
 * {@code AgentMessageHandler} quando o LLM fica indisponível e pela reativação de
 * conversas. Esta classe é o ponto de entrada {@link HandleMessage} para o modo legado.
 */
@ApplicationScoped
public class LegacyMessageHandler extends AbstractMessageHandler {

  @Override
  public String handle(ConversationData data, String rawMessage,
      String userIdentifier, String tenantId) {
    return handleMessage(data, rawMessage, userIdentifier, tenantId);
  }
}
