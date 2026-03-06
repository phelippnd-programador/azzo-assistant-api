package br.com.phdigitalcode.azzo.assistant.llm;

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
 * Extrai slots de linguagem natural usando Ollama como fallback.
 * Chamado SOMENTE quando os extratores determinísticos (OpenNLP, regex) falham.
 * Sempre retorna Optional.empty() em caso de falha — nunca lança exceção.
 */
@ApplicationScoped
public class OllamaSlotExtractor {

    private static final Logger LOG = Logger.getLogger(OllamaSlotExtractor.class);

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
     * Identifica qual serviço o usuário mencionou comparando com a lista disponível.
     * Ex: "quero fazer as unhas" + ["Manicure", "Pedicure"] → Optional.of("Manicure")
     */
    public Optional<String> extractServiceName(String message, List<String> availableServices) {
        if (!enabled || message == null || message.isBlank()
                || availableServices == null || availableServices.isEmpty()) {
            return Optional.empty();
        }

        LOG.infof("[OllamaSlot] Extraindo serviço de: '%s' opções=%s", abbrev(message), availableServices);
        long start = System.currentTimeMillis();
        try {
            String systemPrompt = """
                Você é um extrator de intenção para salão de beleza.
                Identifique qual serviço o usuário quer baseado na lista disponível.
                Responda APENAS com JSON: {"service": "nome exato"} ou {"service": null}

                REGRAS:
                - Retorne o nome EXATAMENTE como aparece na lista de opções
                - Se a intenção do usuário se aproxima de um serviço, escolha esse
                - Se não houver correspondência clara, retorne {"service": null}
                - Não invente serviços fora da lista

                Lista de serviços disponíveis: %s
                """.formatted(String.join(", ", availableServices));

            OllamaChatRequest request = new OllamaChatRequest();
            request.model = model;
            request.stream = false;
            request.format = "json";
            request.options = new OllamaOptions(0.0, 20);
            request.messages = List.of(
                new OllamaMessage("system", systemPrompt),
                new OllamaMessage("user", message)
            );

            OllamaChatResponse response = ollamaRestClient.chat(request);
            if (response == null || response.message == null || response.message.content == null) {
                return Optional.empty();
            }

            JsonNode node = objectMapper.readTree(response.message.content);
            JsonNode serviceNode = node.path("service");
            if (serviceNode.isNull() || serviceNode.isMissingNode()) {
                return Optional.empty();
            }

            String extracted = serviceNode.asText().trim();
            if (extracted.isBlank() || "null".equalsIgnoreCase(extracted)) {
                return Optional.empty();
            }

            // Valida que o resultado está na lista (anti-alucinação)
            boolean isValid = availableServices.stream()
                .anyMatch(s -> s.equalsIgnoreCase(extracted));
            if (!isValid) {
                LOG.warnf("[OllamaSlot] Serviço '%s' não está na lista — descartando", extracted);
                return Optional.empty();
            }

            // Retorna o nome canônico da lista (preserva capitalização original)
            String canonical = availableServices.stream()
                .filter(s -> s.equalsIgnoreCase(extracted))
                .findFirst()
                .orElse(extracted);

            long elapsed = System.currentTimeMillis() - start;
            LOG.infof("[OllamaSlot] Serviço extraído: '%s' em %dms", canonical, elapsed);
            return Optional.of(canonical);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("[OllamaSlot] Extração de serviço falhou após %dms. Causa: %s", elapsed, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Identifica qual profissional o usuário mencionou comparando com a lista disponível.
     * Ex: "pode ser a Maria" + ["Maria Silva", "Ana Costa"] → Optional.of("Maria Silva")
     */
    public Optional<String> extractProfessionalName(String message, List<String> availableProfessionals) {
        if (!enabled || message == null || message.isBlank()
                || availableProfessionals == null || availableProfessionals.isEmpty()) {
            return Optional.empty();
        }

        LOG.infof("[OllamaSlot] Extraindo profissional de: '%s' opções=%s", abbrev(message), availableProfessionals);
        long start = System.currentTimeMillis();
        try {
            String systemPrompt = """
                Você é um extrator de nomes para salão de beleza.
                Identifique qual profissional o usuário quer baseado na lista disponível.
                Responda APENAS com JSON: {"professional": "nome exato"} ou {"professional": null}

                REGRAS:
                - Retorne o nome EXATAMENTE como aparece na lista de opções
                - Correspondências parciais são válidas: "a Maria" → "Maria Silva"
                - Se não houver correspondência clara, retorne {"professional": null}

                Profissionais disponíveis: %s
                """.formatted(String.join(", ", availableProfessionals));

            OllamaChatRequest request = new OllamaChatRequest();
            request.model = model;
            request.stream = false;
            request.format = "json";
            request.options = new OllamaOptions(0.0, 20);
            request.messages = List.of(
                new OllamaMessage("system", systemPrompt),
                new OllamaMessage("user", message)
            );

            OllamaChatResponse response = ollamaRestClient.chat(request);
            if (response == null || response.message == null || response.message.content == null) {
                return Optional.empty();
            }

            JsonNode node = objectMapper.readTree(response.message.content);
            JsonNode profNode = node.path("professional");
            if (profNode.isNull() || profNode.isMissingNode()) {
                return Optional.empty();
            }

            String extracted = profNode.asText().trim();
            if (extracted.isBlank() || "null".equalsIgnoreCase(extracted)) {
                return Optional.empty();
            }

            // Anti-alucinação: resultado deve estar na lista
            boolean isValid = availableProfessionals.stream()
                .anyMatch(p -> p.equalsIgnoreCase(extracted));
            if (!isValid) {
                LOG.warnf("[OllamaSlot] Profissional '%s' não está na lista — descartando", extracted);
                return Optional.empty();
            }

            String canonical = availableProfessionals.stream()
                .filter(p -> p.equalsIgnoreCase(extracted))
                .findFirst()
                .orElse(extracted);

            long elapsed = System.currentTimeMillis() - start;
            LOG.infof("[OllamaSlot] Profissional extraído: '%s' em %dms", canonical, elapsed);
            return Optional.of(canonical);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("[OllamaSlot] Extração de profissional falhou após %dms. Causa: %s", elapsed, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Detecta se o usuário está confirmando ou negando algo em linguagem natural.
     * Ex: "pode confirmar!" → Optional.of(true)
     * Ex: "não quero mais" → Optional.of(false)
     * Ex: "quanto custa?" → Optional.empty()
     */
    public Optional<Boolean> extractConfirmation(String message) {
        if (!enabled || message == null || message.isBlank()) {
            return Optional.empty();
        }

        LOG.infof("[OllamaSlot] Extraindo confirmação de: '%s'", abbrev(message));
        long start = System.currentTimeMillis();
        try {
            String systemPrompt = """
                Você é um extrator de confirmação para assistente de salão de beleza.
                Determine se o usuário está confirmando ou negando uma ação.
                Responda APENAS com JSON: {"confirmed": true}, {"confirmed": false} ou {"confirmed": null}

                REGRAS:
                - true: "sim", "pode", "confirma", "tá bom", "pode ser", "manda", "fecha", "vai", "claro", "quero", "ótimo"
                - false: "não", "nao", "cancela", "para", "desisto", "mudei de ideia", "não quero", "deixa pra lá"
                - null: mensagem ambígua ou fora de contexto de confirmação
                """;

            OllamaChatRequest request = new OllamaChatRequest();
            request.model = model;
            request.stream = false;
            request.format = "json";
            request.options = new OllamaOptions(0.0, 15);
            request.messages = List.of(
                new OllamaMessage("system", systemPrompt),
                new OllamaMessage("user", message)
            );

            OllamaChatResponse response = ollamaRestClient.chat(request);
            if (response == null || response.message == null || response.message.content == null) {
                return Optional.empty();
            }

            JsonNode node = objectMapper.readTree(response.message.content);
            JsonNode confirmedNode = node.path("confirmed");
            if (confirmedNode.isNull() || confirmedNode.isMissingNode()) {
                return Optional.empty();
            }

            if (confirmedNode.isBoolean()) {
                boolean result = confirmedNode.asBoolean();
                long elapsed = System.currentTimeMillis() - start;
                LOG.infof("[OllamaSlot] Confirmação extraída: %s em %dms", result, elapsed);
                return Optional.of(result);
            }

            String asText = confirmedNode.asText("null");
            if ("null".equalsIgnoreCase(asText)) {
                return Optional.empty();
            }

            return Optional.empty();

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("[OllamaSlot] Extração de confirmação falhou após %dms. Causa: %s", elapsed, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extrai o período do dia de expressões naturais que TimePeriod.fromText() não cobre.
     * Ex: "de manhã cedo" → Optional.of("MORNING")
     * Ex: "pós-almoço" → Optional.of("AFTERNOON")
     * Ex: "fim do dia" → Optional.of("NIGHT")
     *
     * @return "MORNING", "AFTERNOON", "NIGHT" ou empty()
     */
    public Optional<String> extractTimePeriod(String message) {
        if (!enabled || message == null || message.isBlank()) {
            return Optional.empty();
        }

        LOG.infof("[OllamaSlot] Extraindo período de: '%s'", abbrev(message));
        long start = System.currentTimeMillis();
        try {
            String systemPrompt = """
                Você é um extrator de período do dia para salão de beleza.
                Determine se o usuário quer atendimento de manhã, tarde ou noite.
                Responda APENAS com JSON: {"period": "MORNING"}, {"period": "AFTERNOON"}, {"period": "NIGHT"} ou {"period": null}

                Mapeamentos:
                - MORNING: manhã, cedo, antes do almoço, de manhã cedo, matutino, pela manhã
                - AFTERNOON: tarde, pós-almoço, depois do almoço, vespertino, à tarde
                - NIGHT: noite, depois das 18, noturno, à noite, fim do dia, no fim do dia
                - null: não consegue determinar ou mensagem ambígua
                """;

            OllamaChatRequest request = new OllamaChatRequest();
            request.model = model;
            request.stream = false;
            request.format = "json";
            request.options = new OllamaOptions(0.0, 15);
            request.messages = List.of(
                new OllamaMessage("system", systemPrompt),
                new OllamaMessage("user", message)
            );

            OllamaChatResponse response = ollamaRestClient.chat(request);
            if (response == null || response.message == null || response.message.content == null) {
                return Optional.empty();
            }

            JsonNode node = objectMapper.readTree(response.message.content);
            JsonNode periodNode = node.path("period");
            if (periodNode.isNull() || periodNode.isMissingNode()) {
                return Optional.empty();
            }

            String period = periodNode.asText().trim().toUpperCase();
            if ("NULL".equals(period) || period.isBlank()) {
                return Optional.empty();
            }

            if (!period.equals("MORNING") && !period.equals("AFTERNOON") && !period.equals("NIGHT")) {
                LOG.warnf("[OllamaSlot] Período inválido retornado pelo modelo: '%s'", period);
                return Optional.empty();
            }

            long elapsed = System.currentTimeMillis() - start;
            LOG.infof("[OllamaSlot] Período extraído: %s em %dms", period, elapsed);
            return Optional.of(period);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("[OllamaSlot] Extração de período falhou após %dms. Causa: %s", elapsed, e.getMessage());
            return Optional.empty();
        }
    }

    private String abbrev(String s) {
        return s != null && s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
