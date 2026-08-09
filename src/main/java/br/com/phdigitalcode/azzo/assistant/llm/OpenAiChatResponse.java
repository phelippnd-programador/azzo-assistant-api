package br.com.phdigitalcode.azzo.assistant.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response no formato OpenAI-compatible, compartilhado entre Groq e o
 * runtime local (llama.cpp server e equivalentes). {@code ignoreUnknown}
 * em todos os niveis para nao quebrar quando o servidor local adicionar
 * campos extras (ex.: variam entre versoes do llama.cpp).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiChatResponse {

    public List<Choice> choices;
    public Usage usage;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        public OllamaMessage message; // reutiliza OllamaMessage {role, content, reasoning_content}

        @JsonProperty("finish_reason")
        public String finishReason;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        public int promptTokens;

        @JsonProperty("completion_tokens")
        public int completionTokens;

        @JsonProperty("total_tokens")
        public int totalTokens;
    }

    /**
     * Extrai o texto da primeira choice. Se "content" vier vazio (alguns
     * modelos de raciocinio deixam o texto final em "reasoning_content"),
     * usa esse campo como fallback. Nunca lanca excecao — retorna null se
     * a estrutura estiver incompleta em qualquer nivel.
     */
    public String text() {
        if (choices == null || choices.isEmpty()) return null;
        Choice first = choices.get(0);
        if (first == null || first.message == null) return null;

        String content = first.message.content;
        if (content != null && !content.isBlank()) return content;

        String reasoning = first.message.reasoningContent;
        return (reasoning != null && !reasoning.isBlank()) ? reasoning : null;
    }

    /** Variante null-safe de {@link #text()} — aceita response nulo sem lançar NPE. */
    public static String extract(OpenAiChatResponse response) {
        return response == null ? null : response.text();
    }

    public int choiceCount() {
        return choices == null ? 0 : choices.size();
    }

    public String finishReason() {
        if (choices == null || choices.isEmpty()) return null;
        return choices.get(0).finishReason;
    }
}
