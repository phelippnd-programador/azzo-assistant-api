package br.com.phdigitalcode.azzo.assistant.llm;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client para a API do Ollama.
 * Configuração: quarkus.rest-client.ollama.url=http://localhost:11434
 */
@Path("/")
@RegisterRestClient(configKey = "ollama")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface OllamaRestClient {

    @POST
    @Path("/api/chat")
    OllamaChatResponse chat(OllamaChatRequest request);
}
