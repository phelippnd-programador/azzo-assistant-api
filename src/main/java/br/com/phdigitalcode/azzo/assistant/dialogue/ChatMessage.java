package br.com.phdigitalcode.azzo.assistant.dialogue;

/**
 * Mensagem do histórico de conversa enviado ao LLM.
 * role: "user" | "assistant" | "tool"
 */
public class ChatMessage {

    public String role;
    public String content;

    public ChatMessage() {}

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
