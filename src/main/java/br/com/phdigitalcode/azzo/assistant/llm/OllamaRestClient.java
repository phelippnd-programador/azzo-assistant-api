package br.com.phdigitalcode.azzo.assistant.llm;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client para o runtime LLM local, hoje um servidor llama.cpp
 * OpenAI-compatible (/chat/completions) — trocado do formato nativo do
 * Ollama (/api/chat). Configuração: quarkus.rest-client.ollama.url
 */
@Path("/")
@RegisterRestClient(configKey = "ollama")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface OllamaRestClient {

    @POST
    @Path("/chat/completions")
    OpenAiChatResponse chat(OpenAiChatRequest request);
}
