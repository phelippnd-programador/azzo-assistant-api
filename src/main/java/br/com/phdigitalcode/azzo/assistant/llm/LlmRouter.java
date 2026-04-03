package br.com.phdigitalcode.azzo.assistant.llm;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    public enum Provider { GROQ, OLLAMA }

    // ─── Circuit Breaker — constantes (compartilhadas por ambos providers) ────

    /** Falhas consecutivas para abrir o circuito. */
    private static final int  CB_FAILURE_THRESHOLD = 3;
    /** Minutos que o circuito permanece ABERTO antes de tentar HALF-OPEN. */
    private static final int  CB_OPEN_DURATION_MIN = 5;
    private static final long CB_OPEN_DURATION_MS  = CB_OPEN_DURATION_MIN * 60_000L;

    // ─── Injeções ─────────────────────────────────────────────────────────────

    @Inject @RestClient OllamaRestClient ollamaClient;
    @Inject @RestClient GroqRestClient   groqClient;
    @Inject LlmUsageRepository           usageRepository;

    @ConfigProperty(name = "assistant.groq.api-key",    defaultValue = "")                     String  groqApiKey;
    @ConfigProperty(name = "assistant.groq.model",      defaultValue = "llama-3.1-8b-instant") String  groqModel;
    @ConfigProperty(name = "assistant.groq.daily-limit",defaultValue = "12000")                int     groqDailyLimit;
    @ConfigProperty(name = "assistant.groq.enabled",    defaultValue = "false")                boolean groqEnabled;
    @ConfigProperty(name = "assistant.ollama.enabled",  defaultValue = "false")                boolean ollamaEnabled;
    @ConfigProperty(name = "assistant.ollama.model",    defaultValue = "azzo-assistant-llama32") String ollamaModel;
    @ConfigProperty(name = "assistant.llm.default-max-tokens", defaultValue = "300")           int     defaultMaxTokens;

    // ─── Estado: contador diário Groq (cache em memória) ─────────────────────

    private final AtomicInteger dailyGroqCount = new AtomicInteger(0);
    private volatile LocalDate  countDay       = null;
    private volatile boolean    dbCountLoaded  = false;

    // ─── Estado: circuit breaker Groq ────────────────────────────────────────

    private volatile int  groqCbFailures = 0;
    private volatile long groqCbOpenedAt = 0L;

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
                && groqCb != CbState.OPEN) {

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
        if (provider == Provider.GROQ) {
            return callGroqWithFallback(messages, maxTokens);
        } else {
            return callOllamaWithFallback(messages, maxTokens);
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
    private LlmResponse callGroqWithFallback(List<OllamaMessage> messages, Integer maxTokens) {
        if (groqCircuitState() == CbState.OPEN) {
            LOG.debugf("[CB-Groq] Circuito ABERTO — indo direto ao Ollama");
            return callOllamaDirectOrError(messages, maxTokens);
        }

        try {
            LlmResponse response = callGroq(messages, maxTokens);
            onGroqSuccess();
            return response;
        } catch (Exception e) {
            onGroqFailure(e.getMessage());
            LOG.warnf("[LlmRouter] Groq falhou (%s) — fallback para Ollama", e.getMessage());
            persistUsage(Provider.OLLAMA);
            return callOllamaDirectOrError(messages, maxTokens);
        }
    }

    /**
     * Tenta Ollama. Se o circuito estiver ABERTO ou a chamada falhar, faz fallback para Groq.
     */
    private LlmResponse callOllamaWithFallback(List<OllamaMessage> messages, Integer maxTokens) {
        if (ollamaCircuitState() == CbState.OPEN) {
            LOG.debugf("[CB-Ollama] Circuito ABERTO — indo direto ao Groq");
            return callGroqDirectOrError(messages, maxTokens);
        }

        try {
            LlmResponse response = callOllama(messages, maxTokens);
            onOllamaSuccess();
            persistUsage(Provider.OLLAMA);
            return response;
        } catch (Exception e) {
            onOllamaFailure(e.getMessage());
            LOG.warnf("[LlmRouter] Ollama falhou (%s) — fallback para Groq", e.getMessage());
            return callGroqDirectOrError(messages, maxTokens);
        }
    }

    /**
     * Chama Groq diretamente (sem re-tentar Ollama) — usado como destino de fallback.
     * Se Groq também estiver indisponível, retorna erro amigável.
     */
    private LlmResponse callGroqDirectOrError(List<OllamaMessage> messages, Integer maxTokens) {
        if (!groqEnabled || groqApiKey.isBlank()) {
            LOG.warn("[LlmRouter] Groq não está habilitado — ambos providers indisponíveis");
            return LlmResponse.error();
        }
        if (groqCircuitState() == CbState.OPEN) {
            LOG.warn("[LlmRouter] CB-Groq também ABERTO — ambos providers indisponíveis");
            return LlmResponse.error();
        }
        try {
            LlmResponse response = callGroq(messages, maxTokens);
            onGroqSuccess();
            persistUsage(Provider.GROQ);
            LOG.infof("[LlmRouter] Groq assumiu como fallback do Ollama");
            return response;
        } catch (Exception e) {
            onGroqFailure(e.getMessage());
            LOG.warnf("[LlmRouter] Groq também falhou como fallback (%s)", e.getMessage());
            return LlmResponse.error();
        }
    }

    /**
     * Chama Ollama diretamente (sem re-tentar Groq) — usado como destino de fallback.
     * Se Ollama também estiver indisponível, retorna erro amigável.
     */
    private LlmResponse callOllamaDirectOrError(List<OllamaMessage> messages, Integer maxTokens) {
        if (!ollamaEnabled) {
            LOG.warn("[LlmRouter] Ollama não está habilitado — ambos providers indisponíveis");
            return LlmResponse.error();
        }
        if (ollamaCircuitState() == CbState.OPEN) {
            LOG.warn("[LlmRouter] CB-Ollama também ABERTO — ambos providers indisponíveis");
            return LlmResponse.error();
        }
        try {
            LlmResponse response = callOllama(messages, maxTokens);
            onOllamaSuccess();
            persistUsage(Provider.OLLAMA);
            LOG.infof("[LlmRouter] Ollama assumiu como fallback do Groq");
            return response;
        } catch (Exception e) {
            onOllamaFailure(e.getMessage());
            LOG.warnf("[LlmRouter] Ollama também falhou como fallback (%s)", e.getMessage());
            return LlmResponse.error();
        }
    }

    // ─── Chamadas brutas ao LLM ───────────────────────────────────────────────

    private LlmResponse callGroq(List<OllamaMessage> messages, Integer maxTokens) {
        int effectiveMaxTokens = resolveMaxTokens(maxTokens);
        GroqChatRequest request = new GroqChatRequest();
        request.model       = groqModel;
        request.messages    = messages;
        request.temperature = 0.2;
        request.maxTokens   = effectiveMaxTokens;
        request.topP        = 0.85;

        GroqChatResponse response = groqClient.chat("Bearer " + groqApiKey, request);
        String text = response.text();
        if (text == null || text.isBlank()) throw new IllegalStateException("Groq retornou resposta vazia");

        int tokensUsed = response.usage != null ? response.usage.total_tokens : 0;
        LOG.debugf("[LlmRouter] Groq respondeu (%d tokens)", tokensUsed);
        return new LlmResponse(text.trim(), Provider.GROQ);
    }

    private LlmResponse callOllama(List<OllamaMessage> messages, Integer maxTokens) {
        int effectiveMaxTokens = resolveMaxTokens(maxTokens);
        OllamaChatRequest request = new OllamaChatRequest();
        request.model    = ollamaModel;
        request.messages = messages;
        request.stream   = false;
        request.options  = new OllamaOptions(0.2, effectiveMaxTokens);

        OllamaChatResponse response = ollamaClient.chat(request);
        if (response == null || response.message == null || response.message.content == null) {
            throw new IllegalStateException("Ollama retornou resposta nula");
        }
        LOG.debugf("[LlmRouter] Ollama respondeu (%d chars)", response.message.content.length());
        return new LlmResponse(response.message.content.trim(), Provider.OLLAMA);
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

    private CbState ollamaCircuitState() {
        if (ollamaCbFailures < CB_FAILURE_THRESHOLD) return CbState.CLOSED;
        long elapsed = System.currentTimeMillis() - ollamaCbOpenedAt;
        return elapsed >= CB_OPEN_DURATION_MS ? CbState.HALF_OPEN : CbState.OPEN;
    }

    private synchronized void onOllamaSuccess() {
        if (ollamaCbFailures > 0) {
            LOG.infof("[CB-Ollama] OK — circuito FECHADO (era %d falhas consecutivas)", ollamaCbFailures);
            ollamaCbFailures = 0;
        }
    }

    private synchronized void onOllamaFailure(String reason) {
        ollamaCbFailures++;
        if (ollamaCbFailures == CB_FAILURE_THRESHOLD) {
            ollamaCbOpenedAt = System.currentTimeMillis();
            LOG.warnf("[CB-Ollama] %d falhas — circuito ABERTO por %d min | %s",
                    CB_FAILURE_THRESHOLD, CB_OPEN_DURATION_MIN, reason);
        } else if (ollamaCbFailures > CB_FAILURE_THRESHOLD) {
            ollamaCbOpenedAt = System.currentTimeMillis();
            LOG.warnf("[CB-Ollama] Falhou em HALF-OPEN — ABERTO novamente | %s", reason);
        } else {
            LOG.warnf("[CB-Ollama] Falha %d/%d | %s", ollamaCbFailures, CB_FAILURE_THRESHOLD, reason);
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
        return s;
    }

    // ─── Tipos ────────────────────────────────────────────────────────────────

    public record LlmResponse(String text, Provider provider) {
        public boolean isError() { return text == null || text.isBlank(); }
        public static LlmResponse error() { return new LlmResponse(null, null); }
    }
}
