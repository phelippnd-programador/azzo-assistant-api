package br.com.phdigitalcode.azzo.assistant.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reaproveitada tanto para montar mensagens de SAÍDA (request) quanto para
 * ler mensagens de ENTRADA (response) — por isso {@code NON_NULL}: campos
 * como "reasoning_content" só existem nas respostas do servidor; se forem
 * serializados como {@code null} numa requisição, alguns chat templates
 * Jinja (ex.: Qwen3, rodando com --jinja no llama-server) falham ao tentar
 * tratar o null como string.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
