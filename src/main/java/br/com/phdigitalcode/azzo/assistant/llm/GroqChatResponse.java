package br.com.phdigitalcode.azzo.assistant.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Response da API do Groq (formato OpenAI).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GroqChatResponse {

    public List<Choice> choices;
    public Usage usage;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        public OllamaMessage message; // reutiliza OllamaMessage {role, content}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        public int prompt_tokens;
        public int completion_tokens;
        public int total_tokens;
    }

    /** Extrai o texto da primeira choice. */
    public String text() {
        if (choices == null || choices.isEmpty()) return null;
        Choice first = choices.get(0);
        if (first.message == null) return null;
        return first.message.content;
    }
}
