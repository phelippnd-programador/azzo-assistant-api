package br.com.phdigitalcode.azzo.assistant.llm;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ProfissionalDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ServicoDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Gera respostas humanizadas usando Ollama.
 *
 * REGRA DE SEGURANÇA: o LLM NUNCA gera dados vindos da API (preços, nomes,
 * especialidades, horários). Ele gera apenas o intro/saudação conversacional.
 * Os dados são sempre montados em Java a partir dos DTOs retornados pela API.
 * Sempre retorna Optional.empty() em caso de falha — o chamador usa fallback hardcoded.
 */
@ApplicationScoped
public class OllamaResponseService {

    private static final Logger LOG = Logger.getLogger(OllamaResponseService.class);

    private static final String INTRO_SYSTEM_PROMPT = """
            Você é Azza, recepcionista virtual de um salão de beleza brasileiro.
            Tom: WhatsApp informal, amigável — como uma amiga atenciosa.
            REGRA CRÍTICA: gere APENAS saudações/intros de 1 linha. NUNCA mencione preços,
            nomes de serviços, especialidades, horários ou qualquer dado numérico.
            Máximo 1 linha. 1 emoji. Português do Brasil informal.
            """;

    @Inject
    @RestClient
    OllamaRestClient ollamaRestClient;

    @ConfigProperty(name = "assistant.ollama.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "assistant.ollama.response.enabled", defaultValue = "true")
    boolean responseEnabled;

    @ConfigProperty(name = "assistant.ollama.model", defaultValue = "gemma2:2b")
    String model;

    /**
     * Gera listagem de serviços com preço, duração e descrição.
     * Preços e dados são SEMPRE montados em Java — LLM gera apenas o intro.
     */
    public Optional<String> generateServicesMessage(String customerName, List<ServicoDto> services) {
        if (!isActive()) return Optional.empty();
        if (services == null || services.isEmpty()) return Optional.empty();

        // Monta a lista de dados em Java — dados da API, nunca do LLM
        String firstName = firstName(customerName);
        StringBuilder dataList = new StringBuilder();
        int i = 1;
        for (ServicoDto s : services.stream().limit(10).toList()) {
            dataList.append("\n").append(i++).append(" - ").append(s.name);
            if (s.price > 0) dataList.append(" — R$").append(String.format(Locale.ROOT, "%.0f", s.price));
            if (s.duration > 0) dataList.append(" | ").append(formatDuration(s.duration));
            if (s.description != null && !s.description.isBlank()) {
                dataList.append("\n   ").append(s.description.trim());
            }
        }
        dataList.append("\n\nManda o número ou o nome do serviço! 😊");

        // Pede ao LLM apenas uma saudação de 1 linha, sem dados
        String introPrompt = "Gere APENAS uma saudação de 1 linha"
                + (firstName != null ? " para " + firstName : "")
                + " para apresentar a lista de serviços do salão. "
                + "NÃO mencione preços, nomes de serviços ou números. Apenas a saudação.";

        Optional<String> intro = callOllama(introPrompt, INTRO_SYSTEM_PROMPT, 40);
        if (intro.isEmpty()) return Optional.empty();

        String introText = safeIntro(intro.get(), firstName != null
                ? "Oi " + firstName + "! Aqui estão nossos serviços 😊"
                : "Aqui estão nossos serviços! 😊");

        return Optional.of(introText + dataList);
    }

    /**
     * Gera listagem de profissionais com especialidades.
     * Nomes e especialidades são SEMPRE montados em Java — LLM gera apenas o intro.
     */
    public Optional<String> generateProfessionalsMessage(String customerName, String serviceName,
            List<ProfissionalDto> professionals) {
        if (!isActive()) return Optional.empty();
        if (professionals == null || professionals.isEmpty()) return Optional.empty();

        // Monta a lista de dados em Java — dados da API, nunca do LLM
        String firstName = firstName(customerName);
        StringBuilder dataList = new StringBuilder();
        int i = 1;
        for (ProfissionalDto p : professionals) {
            dataList.append("\n").append(i++).append(" - ").append(p.name);
            if (p.specialtiesDetailed != null && !p.specialtiesDetailed.isEmpty()) {
                dataList.append(" (").append(p.specialtiesDetailed.get(0).name).append(")");
                String desc = p.specialtiesDetailed.get(0).description;
                if (desc != null && !desc.isBlank()) {
                    dataList.append("\n   ").append(desc.trim());
                }
            }
        }
        dataList.append("\n\nPode mandar o número ou o nome. 😊");

        // Pede ao LLM apenas uma saudação de 1 linha, sem dados
        String introPrompt = "Gere APENAS uma saudação de 1 linha"
                + (firstName != null ? " para " + firstName : "")
                + " para apresentar os profissionais disponíveis para " + serviceName + ". "
                + "NÃO mencione nomes, especialidades ou números. Apenas a saudação.";

        Optional<String> intro = callOllama(introPrompt, INTRO_SYSTEM_PROMPT, 40);
        if (intro.isEmpty()) return Optional.empty();

        String introText = safeIntro(intro.get(), firstName != null
                ? "Oi " + firstName + "! Escolha o profissional 😊"
                : "Escolha o profissional! 😊");

        return Optional.of(introText + dataList);
    }

    /**
     * Gera sugestão de horário destacando o melhor slot disponível.
     * Os horários são passados ao LLM apenas como contexto — não há valores financeiros.
     */
    public Optional<String> generateTimeSlotsMessage(String periodLabel, List<String> slots, String bestSlot) {
        if (!isActive()) return Optional.empty();
        if (slots == null || slots.isEmpty()) return Optional.empty();

        StringBuilder slotList = new StringBuilder();
        for (int i = 0; i < slots.size(); i++) {
            slotList.append(i + 1).append(". ").append(slots.get(i)).append("\n");
        }

        String suggestion = bestSlot != null ? bestSlot : slots.get(0);
        String userPrompt = "Horários disponíveis de " + periodLabel + ":\n"
                + slotList
                + "\nO horário mais conveniente sugerido é " + suggestion + ". "
                + "Apresente os horários de forma amigável, mencione o horário sugerido como recomendação, "
                + "e peça para o cliente escolher pelo número ou digitando o horário. "
                + "Use EXATAMENTE os horários listados acima, não invente outros.";

        return callOllama(userPrompt, null, 200);
    }

    /**
     * Gera sugestão de transferência para atendente humano quando o usuário fica preso.
     */
    public Optional<String> generateHandoffSuggestion() {
        if (!isActive()) return Optional.empty();

        String userPrompt = "O cliente está com dificuldade de avançar no agendamento. "
                + "Gere uma mensagem curta, empática e simpática oferecendo transferência para um atendente humano. "
                + "Instrua o cliente a responder SIM para ser atendido por uma pessoa.";

        return callOllama(userPrompt, null, 80);
    }

    // ─── Infra ────────────────────────────────────────────────────────────────

    private Optional<String> callOllama(String userPrompt, String systemOverride, int maxTokens) {
        long start = System.currentTimeMillis();
        try {
            String systemPrompt = systemOverride != null ? systemOverride : BASE_SYSTEM_PROMPT;
            OllamaChatRequest request = new OllamaChatRequest();
            request.model = model;
            request.stream = false;
            request.options = new OllamaOptions(0.5, maxTokens);
            request.messages = List.of(
                    new OllamaMessage("system", systemPrompt),
                    new OllamaMessage("user", userPrompt));

            OllamaChatResponse response = ollamaRestClient.chat(request);
            long elapsed = System.currentTimeMillis() - start;

            if (response == null || response.message == null
                    || response.message.content == null || response.message.content.isBlank()) {
                LOG.warnf("[OllamaResponse] Resposta vazia após %dms", elapsed);
                return Optional.empty();
            }

            String content = cleanResponse(response.message.content);
            LOG.infof("[OllamaResponse] Gerado em %dms (%d chars)", elapsed, content.length());
            return Optional.of(content);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("[OllamaResponse] Falhou após %dms → usando fallback hardcoded. Causa: %s",
                    elapsed, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Valida o intro gerado pelo LLM: se contiver dígitos (possível alucinação de preço/número),
     * substitui pelo fallback seguro.
     */
    private String safeIntro(String llmIntro, String safeFallback) {
        if (llmIntro == null || llmIntro.isBlank()) return safeFallback;
        String line = llmIntro.lines().findFirst().orElse("").trim();
        // Rejeita se contiver números (R$, preços, quantidades inventadas)
        if (line.matches(".*\\d.*")) {
            LOG.warnf("[OllamaResponse] Intro rejeitada (contém dígitos): %s", line);
            return safeFallback;
        }
        return line;
    }

    private static final String BASE_SYSTEM_PROMPT = """
            Você é Azza, recepcionista virtual de um salão de beleza brasileiro.
            Tom: WhatsApp informal, amigável, acolhedor — como uma amiga atenciosa.
            Regras obrigatórias:
            - Responda SEMPRE em português do Brasil informal
            - Máximo 6 linhas por resposta
            - Use no máximo 2 emojis por mensagem
            - NUNCA invente dados (preços, horários, nomes) que não foram fornecidos
            - Sempre termine com uma instrução clara de como o usuário deve responder
            - Não use asteriscos para negrito, escreva normalmente
            """;

    private String cleanResponse(String text) {
        return text.trim()
                .replaceAll("\n{3,}", "\n\n")
                .replaceAll("[ \\t]+\n", "\n");
    }

    private boolean isActive() {
        return enabled && responseEnabled;
    }

    private String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) return null;
        return fullName.trim().split("\\s+")[0];
    }

    private String formatDuration(int minutes) {
        if (minutes < 60) return minutes + "min";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + "h" : h + "h" + m + "min";
    }
}
