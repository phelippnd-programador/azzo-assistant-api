package br.com.phdigitalcode.azzo.assistant.llm;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client para o runtime LLM local — servidor llama.cpp (llama-server),
 * API OpenAI-compatible em POST /v1/chat/completions. Substituiu o formato
 * nativo do Ollama (/api/chat).
 * Configuração: quarkus.rest-client.local-llm.url (LLM_BASE_URL).
 */
@Path("/v1")
@RegisterRestClient(configKey = "local-llm")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface LocalLlmClient {

    @POST
    @Path("/chat/completions")
    OpenAiChatResponse chat(OpenAiChatRequest request);
}
