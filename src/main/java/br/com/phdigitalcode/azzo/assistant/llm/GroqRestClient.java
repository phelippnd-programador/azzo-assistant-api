package br.com.phdigitalcode.azzo.assistant.llm;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * REST client para a API do Groq (compatível com OpenAI).
 * Configuração: quarkus.rest-client.groq.url=https://api.groq.com
 */
@Path("/")
@RegisterRestClient(configKey = "groq")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface GroqRestClient {

    @POST
    @Path("/openai/v1/chat/completions")
    GroqChatResponse chat(
            @HeaderParam("Authorization") String authorization,
            GroqChatRequest request);
}
