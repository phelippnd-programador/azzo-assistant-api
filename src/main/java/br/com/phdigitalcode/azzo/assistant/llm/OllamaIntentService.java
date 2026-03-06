package br.com.phdigitalcode.azzo.assistant.llm;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.phdigitalcode.azzo.assistant.model.IntentPrediction;
import br.com.phdigitalcode.azzo.assistant.model.IntentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Classificador de intenção via Ollama LLM.
 * Usado como fallback quando o OpenNLP retorna baixa confiança.
 * Sempre retorna Optional.empty() em caso de erro — nunca lança exceção.
 */
@ApplicationScoped
public class OllamaIntentService {

    private static final Logger LOG = Logger.getLogger(OllamaIntentService.class);

    private static final String SYSTEM_PROMPT = """
        Você é um classificador de intenção para assistente WhatsApp de salão de beleza.
        Responda APENAS com JSON válido, sem texto adicional.
        Formato obrigatório: {"intent": "BOOK", "confidence": 0.92}

        Intenções possíveis:
        - BOOK: agendar, marcar, reservar horário novo
        - CANCEL: cancelar, desmarcar horário existente
        - RESCHEDULE: remarcar, trocar dia/hora/profissional
        - LIST: ver meus agendamentos, consultar horários marcados
        - GREETING: saudação simples sem intenção de ação
        - UNKNOWN: pergunta FAQ, off-topic, não identificável

        Exemplos PT-BR informal:
        "quero agendar" → {"intent": "BOOK", "confidence": 0.95}
        "kero agenda hj" → {"intent": "BOOK", "confidence": 0.90}
        "cancela o horario" → {"intent": "CANCEL", "confidence": 0.93}
        "posso remarcar minha escova?" → {"intent": "RESCHEDULE", "confidence": 0.91}
        "quantos horarios tenho?" → {"intent": "LIST", "confidence": 0.88}
        "quanto custa o corte?" → {"intent": "UNKNOWN", "confidence": 0.85}
        "oi tudo bem" → {"intent": "GREETING", "confidence": 0.97}
        """;

    @Inject
    @RestClient
    OllamaRestClient ollamaRestClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "assistant.ollama.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "assistant.ollama.model", defaultValue = "gemma2:2b")
    String model;

    /**
     * Classifica a intenção da mensagem usando Ollama.
     *
     * @param message      mensagem bruta do usuário
     * @param currentStage estágio atual da conversa (contexto para o LLM)
     * @return IntentPrediction se bem-sucedido, empty() caso contrário
     */
    public Optional<IntentPrediction> classify(String message, String currentStage) {
        if (!enabled) {
            LOG.debugf("Ollama desabilitado (assistant.ollama.enabled=false)");
            return Optional.empty();
        }

        LOG.infof("[Ollama] Classificando intent para: '%s' (stage=%s, model=%s)", abbrev(message), currentStage, model);
        long start = System.currentTimeMillis();
        try {
            OllamaChatRequest request = new OllamaChatRequest();
            request.model = model;
            request.stream = false;
            request.format = "json";
            request.options = new OllamaOptions(0.1, 60);
            request.messages = List.of(
                new OllamaMessage("system", SYSTEM_PROMPT + "\nEstágio atual: " + currentStage),
                new OllamaMessage("user", message)
            );

            OllamaChatResponse response = ollamaRestClient.chat(request);
            long elapsed = System.currentTimeMillis() - start;

            if (response == null || response.message == null || response.message.content == null) {
                LOG.warnf("[Ollama] Resposta vazia após %dms", elapsed);
                return Optional.empty();
            }

            JsonNode node = objectMapper.readTree(response.message.content);
            String intentStr = node.path("intent").asText("UNKNOWN");
            double confidence = node.path("confidence").asDouble(0.0);
            IntentType intentType = parseIntent(intentStr);

            LOG.infof("[Ollama] Intent: %s conf=%.2f em %dms (raw='%s')", intentType, confidence, elapsed, response.message.content);
            return Optional.of(new IntentPrediction(intentType, confidence));

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("[Ollama] Indisponível após %dms → fallback OpenNLP. Causa: %s", elapsed, e.getMessage());
            return Optional.empty();
        }
    }

    private IntentType parseIntent(String value) {
        try {
            return IntentType.valueOf(value.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            return IntentType.UNKNOWN;
        }
    }

    private String abbrev(String s) {
        return s != null && s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
