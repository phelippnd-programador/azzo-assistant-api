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

    public OllamaOptions options;
}
