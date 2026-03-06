package br.com.phdigitalcode.azzo.assistant.llm;

public class OllamaMessage {

    public String role;
    public String content;

    public OllamaMessage() {}

    public OllamaMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
