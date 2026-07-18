package br.com.phdigitalcode.azzo.assistant.application.service;

import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;

/**
 * Estratégia de atendimento de uma mensagem do cliente. Cada implementação encapsula
 * um modo de condução da conversa (LLM como agente ou máquina de estados determinística),
 * permitindo ajustar cada caso de forma isolada.
 *
 * <p>A seleção da implementação é feita em {@link AssistantConversationService} conforme
 * a flag {@code assistant.agent.enabled}.</p>
 */
public interface HandleMessage {

  /**
   * Processa a mensagem recebida e devolve a resposta a ser enviada ao cliente,
   * mutando {@code data} com o novo estado da conversa.
   *
   * @param data           estado atual da conversa (fonte de verdade dos slots)
   * @param rawMessage     mensagem crua do cliente
   * @param userIdentifier identificador do usuário (ex.: telefone no WhatsApp)
   * @param tenantId       tenant do salão, em formato String
   * @return texto da resposta ao cliente
   */
  String handle(ConversationData data, String rawMessage, String userIdentifier, String tenantId);
}
