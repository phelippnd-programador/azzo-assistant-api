package br.com.phdigitalcode.azzo.assistant.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OllamaMessage {

    public String role;
    public String content;

    /**
     * Alguns modelos de raciocinio (ex.: DeepSeek-R1, QwQ) colocam o texto
     * final aqui em vez de "content" quando o content vem vazio. Usado
     * somente como fallback em {@link OpenAiChatResponse#text()}.
     */
    @JsonProperty("reasoning_content")
    public String reasoningContent;

    public OllamaMessage() {}

    public OllamaMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
