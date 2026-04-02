package br.com.phdigitalcode.azzo.assistant.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request para a API do Groq (formato OpenAI).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroqChatRequest {

    public String model;
    public List<OllamaMessage> messages; // reutiliza OllamaMessage {role, content}
    public Double temperature;

    @JsonProperty("max_tokens")
    public Integer maxTokens;

    @JsonProperty("top_p")
    public Double topP;
}
