package br.com.phdigitalcode.azzo.assistant.llm;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Extrai datas em linguagem natural usando Ollama.
 * Usado como fallback quando o DateTimeRegexExtractor retorna empty().
 * Sempre retorna Optional.empty() em caso de erro — nunca lança exceção.
 */
@ApplicationScoped
public class OllamaDateEnricher {

    private static final Logger LOG = Logger.getLogger(OllamaDateEnricher.class);

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
     * Tenta extrair uma data da mensagem usando LLM.
     * Retorna Optional.empty() se não encontrar ou se Ollama falhar.
     */
    public Optional<LocalDate> enrich(String rawMessage) {
        if (!enabled) {
            LOG.debugf("Ollama desabilitado, pulando enriquecimento de data");
            return Optional.empty();
        }

        LOG.infof("[Ollama] Extraindo data de: '%s' (model=%s)", rawMessage, model);
        long start = System.currentTimeMillis();
        try {
            LocalDate today = LocalDate.now();
            String systemPrompt = """
                Hoje é %s (formato YYYY-MM-DD).
                Extraia a data mencionada na mensagem do usuário.
                Responda APENAS com JSON: {"date": "YYYY-MM-DD"} ou {"date": null}

                Regras de interpretação:
                - "hoje" → %s
                - "amanhã", "amanha" → %s
                - "sexta", "sexta-feira" → próxima sexta a partir de hoje
                - "semana que vem" → próxima segunda-feira
                - "daqui a 3 dias" → today + 3 dias
                - "próximo sábado", "proximo sabado" → próximo sábado após hoje
                - "fim de semana" → próximo sábado
                - Se não houver data, responda {"date": null}
                """.formatted(today, today, today.plusDays(1));

            OllamaChatRequest request = new OllamaChatRequest();
            request.model = model;
            request.stream = false;
            request.format = "json";
            request.options = new OllamaOptions(0.0, 30);
            request.messages = List.of(
                new OllamaMessage("system", systemPrompt),
                new OllamaMessage("user", rawMessage)
            );

            OllamaChatResponse response = ollamaRestClient.chat(request);
            if (response == null || response.message == null || response.message.content == null) {
                return Optional.empty();
            }

            JsonNode node = objectMapper.readTree(response.message.content);
            JsonNode dateNode = node.path("date");
            if (dateNode.isNull() || dateNode.isMissingNode()) {
                return Optional.empty();
            }

            String dateStr = dateNode.asText();
            if (dateStr == null || dateStr.isBlank() || "null".equalsIgnoreCase(dateStr)) {
                return Optional.empty();
            }

            LocalDate parsed = LocalDate.parse(dateStr);
            long elapsed = System.currentTimeMillis() - start;
            LOG.infof("[Ollama] Data extraída: %s em %dms para: '%s'", parsed, elapsed, rawMessage);
            return Optional.of(parsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("[Ollama] Date enrich falhou após %dms → fallback regex. Causa: %s", elapsed, e.getMessage());
            return Optional.empty();
        }
    }
}
