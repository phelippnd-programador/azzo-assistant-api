package br.com.phdigitalcode.azzo.assistant.llm;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class OllamaChatRequest {

    public String model;
    public List<OllamaMessage> messages;
    public boolean stream = false;

    /** "json" para forçar output estruturado. Null omitido via @JsonInclude. */
    public String format;

    /**
     * Desliga o modo de raciocínio (chain-of-thought) em modelos que suportam
     * "thinking" (ex.: DeepSeek-R1, QwQ, Qwen3). Sem efeito em modelos que não
     * suportam a flag (ex.: llama3.2), mas evita tokens de raciocínio ocultos
     * consumindo tempo/contexto à toa caso o modelo mude no futuro.
     */
    public boolean think = false;

    public OllamaOptions options;
}
