package br.com.phdigitalcode.azzo.assistant.llm;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.phdigitalcode.azzo.assistant.domain.repository.LlmUsageRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Roteador de LLM com fallback bidirecional e circuit breakers independentes.
 *
 * <h3>Estratégias combinadas</h3>
 * <ol>
 *   <li><b>STICKY POR CONVERSA:</b> uma vez que a conversa começa em um provider,
 *       ela termina no mesmo. Evita mudança de "jeito de falar" no meio do diálogo.</li>
 *   <li><b>FALLBACK AUTOMÁTICO:</b> novas conversas vão para Groq enquanto abaixo do
 *       limite diário. Quando passa do limite vai para Ollama.</li>
 *   <li><b>FALLBACK BIDIRECIONAL:</b>
 *       <ul>
 *         <li>Groq fora → Ollama</li>
 *         <li>Ollama fora → Groq (se disponível e abaixo do limite)</li>
 *         <li>Ambos fora → mensagem de erro amigável ao usuário</li>
 *       </ul>
 *   </li>
 *   <li><b>CIRCUIT BREAKER (Groq e Ollama independentes):</b> após
 *       {@value #CB_FAILURE_THRESHOLD} falhas consecutivas, o circuito abre por
 *       {@value #CB_OPEN_DURATION_MIN} minutos — vai direto ao outro provider sem
 *       aguardar timeout.</li>
 * </ol>
 *
 * <pre>
 * Groq CB:   CLOSED ──(3 falhas)──► OPEN ──(5 min)──► HALF-OPEN ──(sucesso)──► CLOSED
 * Ollama CB: CLOSED ──(3 falhas)──► OPEN ──(5 min)──► HALF-OPEN ──(sucesso)──► CLOSED
 * </pre>
 *
 * <p><b>Persistência do contador:</b> o total diário de requisições é salvo no
 * PostgreSQL via {@link LlmUsageRepository}. Sobrevive a restarts.</p>
 */
@ApplicationScoped
public class LlmRouter {

    private static final Logger LOG = Logger.getLogger(LlmRouter.class);
    private static final Pattern GROQ_RETRY_MS_PATTERN =
            Pattern.compile("try again in\\s+(\\d+)ms", Pattern.CASE_INSENSITIVE);

    public enum Provider { GROQ, OLLAMA }

    // ─── Circuit Breaker — constantes (compartilhadas por ambos providers) ────

    /** Falhas consecutivas para abrir o circuito. */
    private static final int  CB_FAILURE_THRESHOLD = 3;
    /** Minutos que o circuito permanece ABERTO antes de tentar HALF-OPEN. */
    private static final int  CB_OPEN_DURATION_MIN = 5;
    private static final long CB_OPEN_DURATION_MS  = CB_OPEN_DURATION_MIN * 60_000L;

    // ─── Injeções ─────────────────────────────────────────────────────────────

    @Inject @RestClient LocalLlmClient localLlmClient;
    @Inject @RestClient GroqRestClient groqClient;
    @Inject LlmUsageRepository         usageRepository;

    @ConfigProperty(name = "assistant.groq.api-key",    defaultValue = "")                     String  groqApiKey;
    @ConfigProperty(name = "assistant.groq.model",      defaultValue = "llama-3.1-8b-instant") String  groqModel;
    @ConfigProperty(name = "assistant.groq.daily-limit",defaultValue = "12000")                int     groqDailyLimit;
    @ConfigProperty(name = "assistant.groq.enabled",    defaultValue = "false")                boolean groqEnabled;
    @ConfigProperty(name = "assistant.ollama.enabled",  defaultValue = "false")                boolean ollamaEnabled;
    @ConfigProperty(name = "assistant.ollama.model",    defaultValue = "azzo-assistant-llama32") String ollamaModel;
    @ConfigProperty(name = "assistant.llm.default-max-tokens", defaultValue = "300")           int     defaultMaxTokens;
    @ConfigProperty(name = "assistant.llm.default-temperature", defaultValue = "0.2")          double  defaultTemperature;
    @ConfigProperty(name = "assistant.groq.rate-limit-cooldown-ms", defaultValue = "15000")    long    groqRateLimitCooldownMs;

    // ─── Estado: contador diário Groq (cache em memória) ─────────────────────

    private final AtomicInteger dailyGroqCount = new AtomicInteger(0);
    private volatile LocalDate  countDay       = null;
    private volatile boolean    dbCountLoaded  = false;

    // ─── Estado: circuit breaker Groq ────────────────────────────────────────

    private volatile int  groqCbFailures = 0;
    private volatile long groqCbOpenedAt = 0L;
    private volatile long groqRateLimitUntilMs = 0L;

    // ─── Estado: circuit breaker Ollama ──────────────────────────────────────

    private volatile int  ollamaCbFailures = 0;
    private volatile long ollamaCbOpenedAt = 0L;

    private enum CbState { CLOSED, OPEN, HALF_OPEN }

    // ─── API pública ──────────────────────────────────────────────────────────

    /**
     * Seleciona o provider para uma conversa.
     *
     * @param activeProvider provider já em uso (sticky), ou null se nova conversa
     * @return provider escolhido
     */
    public Provider select(String activeProvider) {
        // Sticky: conversa já iniciada → mantém o mesmo provider
        if (activeProvider != null) {
            try {
                return Provider.valueOf(activeProvider);
            } catch (IllegalArgumentException ignored) {
                // valor inválido → ignora e re-roteia
            }
        }

        // Nova conversa: decide pelo estado atual
        resetDailyCounterIfNewDay();
        ensureCounterLoaded();

        CbState groqCb = groqCircuitState();

        if (groqEnabled
                && !groqApiKey.isBlank()
                && dailyGroqCount.get() < groqDailyLimit
                && groqCb != CbState.OPEN
                && !isGroqRateLimitCooldownActive()) {

            int newCount = dailyGroqCount.incrementAndGet();
            LOG.debugf("[LlmRouter] GROQ selecionado — uso=%d/%d cb=%s", newCount, groqDailyLimit, groqCb);
            try {
                usageRepository.increment(LocalDate.now(), Provider.GROQ.name());
            } catch (Exception e) {
                LOG.warnf("[LlmRouter] Falha ao persistir contador Groq: %s", e.getMessage());
            }
            return Provider.GROQ;
        }

        LOG.debugf("[LlmRouter] OLLAMA selecionado — groq_enabled=%s uso=%d/%d groq_cb=%s ollama_cb=%s",
                groqEnabled, dailyGroqCount.get(), groqDailyLimit, groqCb, ollamaCircuitState());
        return Provider.OLLAMA;
    }

    /**
     * Chama o LLM com fallback bidirecional.
     *
     * <ul>
     *   <li>Groq fora ou circuito Groq ABERTO → tenta Ollama</li>
     *   <li>Ollama fora ou circuito Ollama ABERTO → tenta Groq</li>
     *   <li>Ambos fora → {@link LlmResponse#error()}</li>
     * </ul>
     */
    public LlmResponse call(Provider provider, String systemPrompt, List<OllamaMessage> messages) {
        return call(provider, systemPrompt, messages, null);
    }

    public LlmResponse call(
            Provider provider,
            String systemPrompt,
            List<OllamaMessage> messages,
            Integer maxTokens) {
        CallOptions options = new CallOptions(defaultTemperature, maxTokens, false);
        if (provider == Provider.GROQ) {
            return callGroqWithFallback(messages, options);
        } else {
            return callOllamaWithFallback(messages, options);
        }
    }

    /**
     * Chamada estruturada (classificação/extração) com fallback bidirecional e
     * circuit breaker, iguais ao {@link #call}. Usada pelos extratores que hoje
     * chamam {@code OllamaRestClient} direto (sem CB nem fallback pro Groq) —
     * essa é a variante que preserva temperatura baixa e o modo JSON que esses
     * extratores exigem.
     */
    public LlmResponse callStructured(
            Provider provider,
            List<OllamaMessage> messages,
            double temperature,
            int maxTokens,
            boolean jsonMode) {
        CallOptions options = new CallOptions(temperature, maxTokens, jsonMode);
        if (provider == Provider.GROQ) {
            return callGroqWithFallback(messages, options);
        } else {
            return callOllamaWithFallback(messages, options);
        }
    }

    /** Retorna o contador Groq do dia atual (cache em memória). */
    public int getDailyGroqCount() {
        resetDailyCounterIfNewDay();
        ensureCounterLoaded();
        return dailyGroqCount.get();
    }

    /** Retorna o limite diário configurado para o Groq. */
    public int getGroqDailyLimit() {
        return groqDailyLimit;
    }

    /** Retorna o estado dos dois circuit breakers para o endpoint de métricas. */
    public Map<String, Object> getCircuitBreakerStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("groq",  buildCbStatus("groq",  groqCircuitState(),  groqCbFailures,  groqCbOpenedAt));
        status.put("ollama", buildCbStatus("ollama", ollamaCircuitState(), ollamaCbFailures, ollamaCbOpenedAt));
        return status;
    }

    // ─── Fluxos de chamada com fallback ───────────────────────────────────────

    /**
     * Tenta Groq. Se o circuito estiver ABERTO ou a chamada falhar, faz fallback para Ollama.
     */
    private LlmResponse callGroqWithFallback(List<OllamaMessage> messages, CallOptions options) {
        if (groqCircuitState() == CbState.OPEN || isGroqRateLimitCooldownActive()) {
            LOG.debugf("[CB-Groq] Circuito ABERTO — indo direto ao Ollama");
            return callOllamaDirectOrError(messages, options);
        }

        try {
            LlmResponse response = callGroq(messages, options);
            onGroqSuccess();
            return response;
        } catch (Exception e) {
            String reason = describeFailure(e);
            if (isGroqRateLimitError(e.getMessage())) {
                onGroqRateLimited(e.getMessage());
            } else {
                onGroqFailure(reason);
            }
            LOG.warnf("[LlmRouter] Groq falhou (%s) — fallback para LLM local", reason);
            persistUsage(Provider.OLLAMA);
            return callOllamaDirectOrError(messages, options);
        }
    }

    /**
     * Tenta o LLM local. Se o circuito estiver ABERTO ou a chamada falhar, faz fallback para Groq.
     */
    private LlmResponse callOllamaWithFallback(List<OllamaMessage> messages, CallOptions options) {
        if (ollamaCircuitState() == CbState.OPEN) {
            LOG.debugf("[CB-LlamaCpp] Circuito ABERTO — indo direto ao Groq");
            return callGroqDirectOrError(messages, options);
        }

        try {
            LlmResponse response = callOllama(messages, options);
            onOllamaSuccess();
            persistUsage(Provider.OLLAMA);
            return response;
        } catch (Exception e) {
            String reason = describeFailure(e);
            onOllamaFailure(reason);
            LOG.warnf("[LlmRouter] LLM local falhou (%s) — fallback para Groq", reason);
            return callGroqDirectOrError(messages, options);
        }
    }

    /**
     * Chama Groq diretamente (sem re-tentar Ollama) — usado como destino de fallback.
     * Se Groq também estiver indisponível, retorna erro amigável.
     */
    private LlmResponse callGroqDirectOrError(List<OllamaMessage> messages, CallOptions options) {
        if (!groqEnabled || groqApiKey.isBlank()) {
            LOG.warn("[LlmRouter] Groq não está habilitado — ambos providers indisponíveis");
            return LlmResponse.error();
        }
        if (groqCircuitState() == CbState.OPEN || isGroqRateLimitCooldownActive()) {
            LOG.warn("[LlmRouter] CB-Groq também ABERTO — ambos providers indisponíveis");
            return LlmResponse.error();
        }
        try {
            LlmResponse response = callGroq(messages, options);
            onGroqSuccess();
            persistUsage(Provider.GROQ);
            LOG.infof("[LlmRouter] Groq assumiu como fallback do LLM local");
            return response;
        } catch (Exception e) {
            String reason = describeFailure(e);
            if (isGroqRateLimitError(e.getMessage())) {
                onGroqRateLimited(e.getMessage());
            } else {
                onGroqFailure(reason);
            }
            LOG.warnf("[LlmRouter] Groq também falhou como fallback (%s)", reason);
            return LlmResponse.error();
        }
    }

    /**
     * Chama o LLM local diretamente (sem re-tentar Groq) — usado como destino de fallback.
     * Se o LLM local também estiver indisponível, retorna erro amigável.
     */
    private LlmResponse callOllamaDirectOrError(List<OllamaMessage> messages, CallOptions options) {
        if (!ollamaEnabled) {
            LOG.warn("[LlmRouter] LLM local não está habilitado — ambos providers indisponíveis");
            return LlmResponse.error();
        }
        if (ollamaCircuitState() == CbState.OPEN) {
            LOG.warn("[LlmRouter] CB-LlamaCpp também ABERTO — ambos providers indisponíveis");
            return LlmResponse.error();
        }
        try {
            LlmResponse response = callOllama(messages, options);
            onOllamaSuccess();
            persistUsage(Provider.OLLAMA);
            LOG.infof("[LlmRouter] LLM local assumiu como fallback do Groq");
            return response;
        } catch (Exception e) {
            String reason = describeFailure(e);
            onOllamaFailure(reason);
            LOG.warnf("[LlmRouter] LLM local também falhou como fallback (%s)", reason);
            return LlmResponse.error();
        }
    }

    // ─── Chamadas brutas ao LLM ───────────────────────────────────────────────

    private LlmResponse callGroq(List<OllamaMessage> messages, CallOptions options) {
        OpenAiChatRequest request = new OpenAiChatRequest();
        request.model       = groqModel;
        request.messages    = messages;
        request.temperature = options.temperature();
        request.maxTokens   = resolveMaxTokens(options.maxTokens());
        request.topP        = 0.85;
        if (options.jsonMode()) {
            request.responseFormat = new OpenAiChatRequest.ResponseFormat("json_object");
        }

        OpenAiChatResponse response = groqClient.chat("Bearer " + groqApiKey, request);
        String text = response != null ? response.text() : null;
        if (text == null || text.isBlank()) throw new IllegalStateException("Groq retornou resposta vazia");

        int tokensUsed = response.usage != null ? response.usage.totalTokens : 0;
        LOG.debugf("[LlmRouter] Groq respondeu (%d tokens)", tokensUsed);
        return new LlmResponse(text.trim(), Provider.GROQ);
    }

    /**
     * Chama o LLM local (llama.cpp server, OpenAI-compatible). Valida a
     * resposta em cada nivel (objeto nulo, choices nulo/vazio, message nulo,
     * conteudo vazio) para diferenciar exatamente onde a resposta veio
     * incompleta — nunca lança NullPointerException.
     */
    private LlmResponse callOllama(List<OllamaMessage> messages, CallOptions options) {
        long start = System.currentTimeMillis();
        OpenAiChatRequest request = new OpenAiChatRequest();
        request.model       = ollamaModel;
        request.messages    = messages;
        request.stream      = false; // a aplicacao nao processa streaming
        request.temperature = options.temperature();
        request.maxTokens   = resolveMaxTokens(options.maxTokens());
        if (options.jsonMode()) {
            request.responseFormat = new OpenAiChatRequest.ResponseFormat("json_object");
        }

        OpenAiChatResponse response = localLlmClient.chat(request);
        long elapsedMs = System.currentTimeMillis() - start;

        if (response == null) {
            throw new IllegalStateException("resposta nula do LLM local");
        }
        if (response.choices == null || response.choices.isEmpty()) {
            LOG.warnf("[LlmRouter] LLM local: choices vazio/nulo (model=%s elapsedMs=%d)", ollamaModel, elapsedMs);
            throw new IllegalStateException("lista 'choices' vazia na resposta do LLM local");
        }
        OpenAiChatResponse.Choice first = response.choices.get(0);
        if (first == null || first.message == null) {
            throw new IllegalStateException("'message' nula na resposta do LLM local");
        }

        String text = response.text();
        if (text == null || text.isBlank()) {
            LOG.warnf("[LlmRouter] LLM local: conteudo vazio (model=%s elapsedMs=%d finishReason=%s choices=%d)",
                    ollamaModel, elapsedMs, first.finishReason, response.choiceCount());
            throw new IllegalStateException("conteúdo vazio na resposta do LLM local");
        }

        LOG.infof("[LlmRouter] LLM local respondeu: model=%s elapsedMs=%d finishReason=%s "
                        + "promptTokens=%d completionTokens=%d totalTokens=%d choices=%d",
                ollamaModel, elapsedMs, first.finishReason,
                response.usage != null ? response.usage.promptTokens : 0,
                response.usage != null ? response.usage.completionTokens : 0,
                response.usage != null ? response.usage.totalTokens : 0,
                response.choiceCount());
        return new LlmResponse(text.trim(), Provider.OLLAMA);
    }

    /**
     * Classifica a falha numa categoria legível (timeout, HTTP 4xx/5xx,
     * conexão recusada, JSON inválido, ou uma das mensagens estruturais já
     * lançadas por {@link #callOllama}) em vez de só repassar a exceção
     * bruta — facilita diagnosticar rapidamente pelos logs qual das duas
     * pontas (rede vs. formato de resposta) está falhando.
     */
    static String describeFailure(Exception e) {
        if (e instanceof jakarta.ws.rs.WebApplicationException wae) {
            int status = wae.getResponse() != null ? wae.getResponse().getStatus() : -1;
            // Surface o corpo do erro do provedor (ex.: Groq "model_decommissioned",
            // "invalid_api_key", limite de tokens) para diagnostico — antes so mostrava
            // o status e o motivo real ficava invisivel. Nunca inclui a API key.
            String corpo = lerCorpoErro(wae);
            String extra = corpo.isBlank() ? "" : " | " + corpo;
            if (status >= 500) return "HTTP " + status + " (erro no servidor LLM)" + extra;
            if (status >= 400) return "HTTP " + status + " (requisição rejeitada)" + extra;
            return "HTTP " + status + extra;
        }

        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof java.net.ConnectException) {
            return "conexão recusada (" + cause.getMessage() + ")";
        }
        if (cause instanceof java.net.SocketTimeoutException
                || cause instanceof java.util.concurrent.TimeoutException
                || (e.getMessage() != null && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout"))) {
            return "timeout";
        }
        if (cause instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            return "JSON inválido na resposta (" + cause.getMessage() + ")";
        }
        // Mensagens estruturais ja legiveis lancadas por callOllama/callGroq
        // (resposta nula, choices vazio, message nula, conteudo vazio, resposta vazia).
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    /** Lê um trecho curto do corpo do erro HTTP do provedor, sem nunca expor a API key. */
    private static String lerCorpoErro(jakarta.ws.rs.WebApplicationException wae) {
        try {
            var response = wae.getResponse();
            if (response == null || !response.hasEntity()) return "";
            response.bufferEntity();
            String corpo = response.readEntity(String.class);
            if (corpo == null) return "";
            corpo = corpo.replaceAll("\\s+", " ").trim();
            return corpo.substring(0, Math.min(300, corpo.length()));
        } catch (Exception ignored) {
            return "";
        }
    }

    private int resolveMaxTokens(Integer requestedMaxTokens) {
        int base = requestedMaxTokens == null ? defaultMaxTokens : requestedMaxTokens;
        return Math.max(24, base);
    }

    private void persistUsage(Provider provider) {
        try {
            usageRepository.increment(LocalDate.now(), provider.name());
        } catch (Exception e) {
            LOG.warnf("[LlmRouter] Falha ao persistir contador %s: %s", provider, e.getMessage());
        }
    }

    // ─── Circuit Breaker Groq ─────────────────────────────────────────────────

    private CbState groqCircuitState() {
        if (groqCbFailures < CB_FAILURE_THRESHOLD) return CbState.CLOSED;
        long elapsed = System.currentTimeMillis() - groqCbOpenedAt;
        return elapsed >= CB_OPEN_DURATION_MS ? CbState.HALF_OPEN : CbState.OPEN;
    }

    private synchronized void onGroqSuccess() {
        if (groqCbFailures > 0) {
            LOG.infof("[CB-Groq] OK — circuito FECHADO (era %d falhas consecutivas)", groqCbFailures);
            groqCbFailures = 0;
        }
        groqRateLimitUntilMs = 0L;
    }

    private synchronized void onGroqFailure(String reason) {
        groqCbFailures++;
        if (groqCbFailures == CB_FAILURE_THRESHOLD) {
            groqCbOpenedAt = System.currentTimeMillis();
            LOG.warnf("[CB-Groq] %d falhas — circuito ABERTO por %d min | %s",
                    CB_FAILURE_THRESHOLD, CB_OPEN_DURATION_MIN, reason);
        } else if (groqCbFailures > CB_FAILURE_THRESHOLD) {
            groqCbOpenedAt = System.currentTimeMillis();
            LOG.warnf("[CB-Groq] Falhou em HALF-OPEN — ABERTO novamente | %s", reason);
        } else {
            LOG.warnf("[CB-Groq] Falha %d/%d | %s", groqCbFailures, CB_FAILURE_THRESHOLD, reason);
        }
    }

    // ─── Circuit Breaker Ollama ───────────────────────────────────────────────

    private synchronized void onGroqRateLimited(String reason) {
        long cooldownMs = Math.max(groqRateLimitCooldownMs, extractRetryDelayMs(reason));
        groqRateLimitUntilMs = System.currentTimeMillis() + cooldownMs;
        LOG.warnf("[Groq-TPM] Cooldown ativado por %dms | %s", cooldownMs, reason);
    }

    private CbState ollamaCircuitState() {
        if (ollamaCbFailures < CB_FAILURE_THRESHOLD) return CbState.CLOSED;
        long elapsed = System.currentTimeMillis() - ollamaCbOpenedAt;
        return elapsed >= CB_OPEN_DURATION_MS ? CbState.HALF_OPEN : CbState.OPEN;
    }

    private synchronized void onOllamaSuccess() {
        if (ollamaCbFailures > 0) {
            LOG.infof("[CB-LlamaCpp] OK — circuito FECHADO (era %d falhas consecutivas)", ollamaCbFailures);
            ollamaCbFailures = 0;
        }
    }

    private synchronized void onOllamaFailure(String reason) {
        ollamaCbFailures++;
        if (ollamaCbFailures == CB_FAILURE_THRESHOLD) {
            ollamaCbOpenedAt = System.currentTimeMillis();
            LOG.warnf("[CB-LlamaCpp] %d falhas — circuito ABERTO por %d min | %s",
                    CB_FAILURE_THRESHOLD, CB_OPEN_DURATION_MIN, reason);
        } else if (ollamaCbFailures > CB_FAILURE_THRESHOLD) {
            ollamaCbOpenedAt = System.currentTimeMillis();
            LOG.warnf("[CB-LlamaCpp] Falhou em HALF-OPEN — ABERTO novamente | %s", reason);
        } else {
            LOG.warnf("[CB-LlamaCpp] Falha %d/%d | %s", ollamaCbFailures, CB_FAILURE_THRESHOLD, reason);
        }
    }

    // ─── Controle do contador diário ─────────────────────────────────────────

    private void ensureCounterLoaded() {
        if (dbCountLoaded) return;
        synchronized (this) {
            if (dbCountLoaded) return;
            try {
                int dbCount = usageRepository.getCount(LocalDate.now(), Provider.GROQ.name());
                dailyGroqCount.set(dbCount);
                dbCountLoaded = true;
                LOG.infof("[LlmRouter] Contador Groq carregado do banco: %d/%d", dbCount, groqDailyLimit);
            } catch (Exception e) {
                LOG.warnf("[LlmRouter] Não foi possível carregar contador do banco (%s) — usando %d",
                        e.getMessage(), dailyGroqCount.get());
            }
        }
    }

    private void resetDailyCounterIfNewDay() {
        LocalDate today = LocalDate.now();
        if (today.equals(countDay)) return;
        synchronized (this) {
            if (today.equals(countDay)) return;
            if (countDay != null) {
                LOG.infof("[LlmRouter] Novo dia (%s) — contador Groq em memória resetado (era %d)",
                        today, dailyGroqCount.get());
            }
            dailyGroqCount.set(0);
            countDay      = today;
            dbCountLoaded = false;
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildCbStatus(String name, CbState state, int failures, long openedAt) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("state",                state.name());
        s.put("consecutive_failures", failures);
        s.put("failure_threshold",    CB_FAILURE_THRESHOLD);
        if (state == CbState.OPEN) {
            long remainingSec = Math.max(0, (CB_OPEN_DURATION_MS - (System.currentTimeMillis() - openedAt)) / 1000);
            s.put("retry_in_seconds", remainingSec);
        }
        if ("groq".equals(name) && isGroqRateLimitCooldownActive()) {
            long remainingSec = Math.max(0, (groqRateLimitUntilMs - System.currentTimeMillis()) / 1000);
            s.put("rate_limit_cooldown_seconds", remainingSec);
        }
        return s;
    }

    private boolean isGroqRateLimitCooldownActive() {
        return groqRateLimitUntilMs > System.currentTimeMillis();
    }

    private boolean isGroqRateLimitError(String reason) {
        if (reason == null || reason.isBlank()) return false;
        String normalized = reason.toLowerCase();
        return normalized.contains("rate_limit_exceeded")
                || normalized.contains("rate limit reached")
                || normalized.contains("tokens per minute")
                || normalized.contains("too many requests");
    }

    private long extractRetryDelayMs(String reason) {
        if (reason == null || reason.isBlank()) return 0L;
        Matcher matcher = GROQ_RETRY_MS_PATTERN.matcher(reason);
        if (!matcher.find()) return 0L;
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    // ─── Tipos ────────────────────────────────────────────────────────────────

    public record LlmResponse(String text, Provider provider) {
        public boolean isError() { return text == null || text.isBlank(); }
        public static LlmResponse error() { return new LlmResponse(null, null); }
    }

    private record CallOptions(double temperature, Integer maxTokens, boolean jsonMode) {}
}
