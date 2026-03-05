package br.com.phdigitalcode.azzo.assistant.api.rest;

import java.util.Map;

import br.com.phdigitalcode.azzo.assistant.application.service.AssistantConversationService;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageRequest;
import br.com.phdigitalcode.azzo.assistant.model.AssistantMessageResponse;
import br.com.phdigitalcode.azzo.assistant.training.OpenNLPModelTrainer;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/v1/assistant")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AssistantResource {

  @Inject AssistantConversationService conversationService;
  @Inject OpenNLPModelTrainer modelTrainer;

  /**
   * Processa uma mensagem do usuário via WhatsApp.
   *
   * Headers obrigatórios:
   * - X-Tenant-Id: UUID do tenant (enviado pelo backend principal)
   * - X-User-Identifier: telefone ou e-mail do usuário
   * - X-User-Name: nome do usuário (opcional, vindo do WhatsApp profile)
   */
  @POST
  @Path("/message")
  public AssistantMessageResponse message(
      @Valid AssistantMessageRequest request,
      @HeaderParam("X-User-Identifier") String userIdentifier,
      @HeaderParam("X-User-Name") String userName) {
    return conversationService.process(request.message, userIdentifier, userName);
  }

  /**
   * Força o retreinamento dos modelos OpenNLP.
   * Usado após adicionar novos dados de treinamento.
   */
  @POST
  @Path("/admin/retrain")
  public Map<String, Object> retrain() {
    modelTrainer.forceRetrain();
    return Map.of("status", "OK", "message", "Modelos OpenNLP retreinados com sucesso.");
  }
}
