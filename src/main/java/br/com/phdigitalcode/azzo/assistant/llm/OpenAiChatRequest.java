package br.com.phdigitalcode.azzo.assistant.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Request no formato OpenAI-compatible (/v1/chat/completions ou
 * /chat/completions), usado tanto pro Groq quanto pro runtime local
 * (llama.cpp server e equivalentes) — os dois falam o mesmo protocolo.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAiChatRequest {

    public String model;
    public List<OllamaMessage> messages; // reutiliza OllamaMessage {role, content}
    public Double temperature;

    @JsonProperty("max_tokens")
    public Integer maxTokens;

    @JsonProperty("top_p")
    public Double topP;

    public Boolean stream;

    @JsonProperty("response_format")
    public ResponseFormat responseFormat;

    public static class ResponseFormat {
        public String type;

        public ResponseFormat() {}

        public ResponseFormat(String type) {
            this.type = type;
        }
    }
}
