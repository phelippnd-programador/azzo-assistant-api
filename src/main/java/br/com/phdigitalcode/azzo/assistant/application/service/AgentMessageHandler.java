package br.com.phdigitalcode.azzo.assistant.application.service;

import br.com.phdigitalcode.azzo.assistant.dialogue.ChatMessage;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import br.com.phdigitalcode.azzo.assistant.llm.LlmBookingAgent;
import br.com.phdigitalcode.azzo.assistant.util.TextNormalizer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

/**
 * Fluxo orientado a LLM: o modelo conduz toda a conversa com contexto completo do salão
 * e o Java executa as ações detectadas nos action tokens. Robustez determinística
 * (locks, criação na confirmação, extração de data, anti-contradição) é herdada de
 * {@link AbstractMessageHandler}. Se o LLM ficar indisponível, cai para a máquina de
 * estados legada ({@link #handleMessage}).
 */
@ApplicationScoped
public class AgentMessageHandler extends AbstractMessageHandler {

  @Override
  public String handle(ConversationData data, String rawMessage,
      String userIdentifier, String tenantId) {

    // Confirmação de presença via lembrete automático — tratamento determinístico,
    // sem envolver o LLM. Tem prioridade sobre qualquer outro estágio.
    if (data.stage == ConversationStage.AWAITING_APPOINTMENT_CONFIRMATION) {
      String normalized = TextNormalizer.normalize(rawMessage);
      return handleReminderConfirmation(data, normalized, userIdentifier, tenantId);
    }
    if (data.stage == ConversationStage.AWAITING_REACTIVATION_REPLY) {
      String normalized = TextNormalizer.normalize(rawMessage);
      return handleReactivationReply(data, rawMessage, normalized, userIdentifier, tenantId);
    }

    // Conversa anterior finalizada → limpa slots para nova sessão, mas mantém nome do cliente
    if (data.stage == ConversationStage.COMPLETED) {
      LOG.debugf("[Agent] Stage=COMPLETED — resetando slots para nova conversa");
      String savedName = data.customerName;
      data.reset();
      data.customerName = savedName; // mantém o nome para não precisar perguntar de novo
    }

    String normalized = TextNormalizer.normalize(rawMessage);

    if (isServiceCatalogQuery(normalized)) {
      clearBookingFlow(data, tenantId);
      data.stage = ConversationStage.ASK_SERVICE;
      return domainService.formatServicesPromptForCustomer(tenantId, data.customerName);
    }

    // Limita o histórico a 30 mensagens para não estourar o contexto do LLM
    BookingLeadSignals bookingLead = detectBookingLeadSignals(rawMessage, data, tenantId);
    applyBookingLeadSignals(data, bookingLead);
    syncBookingStageFromKnownSlots(data);
    trimChatHistory(data);
    PreparedSlotContext preparedSlotContext = prepareAvailableSlotsForAgent(data, normalized, tenantId);
    String shortcutReply = handleAgentDeterministicIntent(data, rawMessage, normalized, bookingLead, userIdentifier, tenantId);
    if (shortcutReply != null) {
      return shortcutReply;
    }

    // Confirmação de agendamento tratada deterministicamente (sem LLM), garantindo
    // a pergunta detalhada ("Deseja confirmar ... com <profissional> no dia <data> as <hora>?")
    // e criação apenas após o "sim" do cliente.
    String confirmationReply = handleAgentBookingConfirmationFlow(data, rawMessage, normalized, userIdentifier, tenantId);
    if (confirmationReply != null) {
      return confirmationReply;
    }

    // Resolve datas relativas em Java antes de enviar ao LLM (modelos 8B erram esse cálculo)
    String contextualMessage = contextualizeAgentSelection(data, rawMessage, normalized);
    contextualMessage = appendBookingContextForAgent(data, contextualMessage, bookingLead);
    contextualMessage = appendPreparedSlotsForAgent(contextualMessage, preparedSlotContext);
    String enrichedMessage = enrichDatesInMessage(contextualMessage);
    String compactedMessage = compactMessageForLlm(enrichedMessage);
    LlmBookingAgent.AgentChatOptions chatOptions =
        buildAgentChatOptions(data, rawMessage, tenantId, userIdentifier, preparedSlotContext);

    String systemPrompt = agentSystemPromptBuilder.build(tenantId);
    // Passa activeProvider para sticky routing — null = nova conversa, router decide
    LlmBookingAgent.AgentResult result = llmBookingAgent.chat(
        systemPrompt, data.chatHistory, compactedMessage, data.activeProvider, chatOptions);

    // Persiste o provider escolhido para manter sticky durante toda a conversa
    if (result.providerUsed() != null) {
      data.activeProvider = result.providerUsed();
    }

    // Safety net: se o LLM pediu confirmação, o cliente confirmou, mas o LLM esqueceu de
    // emitir [CRIAR_AGENDAMENTO], re-chama com hint explícito (1 tentativa).
    if (result.actions().isEmpty()
        && isConfirmationPending(data.chatHistory)
        && isAffirmativeResponse(rawMessage)) {
      LOG.infof("[Agent] Confirmação detectada sem action token — re-chamando LLM com hint");
      List<ChatMessage> tempHistory = new ArrayList<>(data.chatHistory);
      tempHistory.add(new ChatMessage("user", compactedMessage));
      tempHistory.add(new ChatMessage("tool",
          "[Sistema: o cliente acabou de confirmar o agendamento. "
          + "Emita OBRIGATORIAMENTE [CRIAR_AGENDAMENTO:...] com todos os dados coletados na conversa. "
          + "Sem esse token, nenhum agendamento será criado no sistema.]"));
      LlmBookingAgent.AgentResult retry = llmBookingAgent.chat(
          systemPrompt, tempHistory, "", data.activeProvider);
      if (retry.hasAction("CRIAR_AGENDAMENTO")) {
        LOG.infof("[Agent] Re-chamada retornou CRIAR_AGENDAMENTO — usando resultado do retry");
        result = retry;
      } else {
        LOG.warnf("[Agent] Re-chamada ainda sem CRIAR_AGENDAMENTO — seguindo com resultado original");
      }
    }

    // LLM completamente indisponível (Groq + Ollama falharam) → cai para máquina de estados
    if (result.llmUnavailable()) {
      LOG.warnf("[Agent] LLM indisponível — usando fallback determinístico para tenantId=%s", tenantId);
      return handleMessage(data, rawMessage, userIdentifier, tenantId);
    }

    // Processa ações — max 1 round-trip para evitar loops
    String finalReply = processActions(result, data, rawMessage, userIdentifier, tenantId, systemPrompt);
    // Validação independente de modelo/provedor: nunca deixa entrar no histórico
    // (nem ser enviada ao cliente) uma resposta que contradiz dados já confirmados
    // no estado — ex.: "não identifiquei o profissional" quando data.professionalId
    // já é conhecido. A causa raiz da contradição foi corrigida acima; isto é uma
    // rede de segurança adicional, agnóstica a qual LLM do pool respondeu.
    finalReply = sanitizeReplyAgainstKnownState(data, finalReply, tenantId);
    // Ancora a confirmação nos slots REALMENTE resolvidos (backend é fonte de verdade):
    // impede que o LLM confirme um serviço diferente do que sera agendado.
    finalReply = anchorConfirmationToResolvedSlots(data, finalReply);

    // Grava no histórico para próximos turnos (usa mensagem enriquecida para consistência)
    data.chatHistory.add(new ChatMessage("user", compactedMessage));
    data.chatHistory.add(new ChatMessage("assistant", finalReply));
    trimChatHistory(data);

    return finalReply;
  }
}
