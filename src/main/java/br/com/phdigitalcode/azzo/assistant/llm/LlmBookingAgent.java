package br.com.phdigitalcode.azzo.assistant.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.phdigitalcode.azzo.assistant.dialogue.ChatMessage;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmMessage;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmResponse;
import br.com.phdigitalcode.azzo.assistant.llm.pool.execution.LlmPoolExecutor;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Agente LLM principal: envia o histórico completo da conversa ao LLM
 * (via LlmRouter) e retorna a resposta limpa + lista de ações detectadas.
 *
 * O LlmRouter decide automaticamente se usa Groq ou Ollama:
 * - Sticky por conversa: mantém o mesmo provider durante todo o diálogo
 * - Fallback automático: se Groq falhar, tenta Ollama
 *
 * Action tokens emitidos pelo LLM (no final da mensagem):
 *   [CONSULTAR_HORARIOS:prof=P1|date=YYYY-MM-DD|svc=S1]
 *   [CRIAR_AGENDAMENTO:svc=S1|prof=P1|date=YYYY-MM-DD|time=HH:MM|customer=Nome]
 *   [CANCELAR_AGENDAMENTO:appointment_id=UUID]
 */
@ApplicationScoped
public class LlmBookingAgent {

    private static final Logger LOG = Logger.getLogger(LlmBookingAgent.class);

    private static final Pattern ACTION_PATTERN =
            Pattern.compile("\\[([A-Z_]+):([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);

    @Inject
    LlmRouter llmRouter;

    @Inject
    LlmPoolExecutor poolExecutor;

    @ConfigProperty(name = "assistant.llm.pool.enabled", defaultValue = "false")
    boolean poolEnabled;

    void onStart(@Observes StartupEvent ev) {
        LOG.infof("[LlmAgent] Pool de provedores de LLM: %s (assistant.llm.pool.enabled=%s)",
                poolEnabled ? "HABILITADO" : "DESABILITADO — usando fluxo legado (Groq/Ollama)", poolEnabled);
    }

    // ─── API pública ──────────────────────────────────────────────────────────

    /**
     * Envia mensagem ao LLM com o histórico completo.
     *
     * @param systemPrompt   prompt do sistema com catálogo do salão
     * @param history        histórico de mensagens anteriores
     * @param userMessage    nova mensagem do usuário
     * @param activeProvider provider já em uso na conversa (sticky), ou null se nova
     * @return resultado com texto limpo, ações detectadas e provider usado
     */
    public AgentResult chat(String systemPrompt, List<ChatMessage> history,
            String userMessage, String activeProvider) {
        return chat(systemPrompt, history, userMessage, activeProvider, AgentChatOptions.defaultOptions());
    }

    public AgentResult chat(String systemPrompt, List<ChatMessage> history,
            String userMessage, String activeProvider, AgentChatOptions options) {

        long start = System.currentTimeMillis();
        try {
            AgentChatOptions effectiveOptions = options == null ? AgentChatOptions.defaultOptions() : options;
            String effectiveSystemPrompt = applyRuntimeInstruction(systemPrompt, effectiveOptions.runtimeInstruction());

            // Novo pool de provedores (atrás do flag). Se estiver ligado e houver
            // capacidade, conduz a chamada; caso contrário, cai para o fluxo legado.
            if (poolEnabled) {
                AgentResult poolResult = chatViaPool(effectiveSystemPrompt, history, userMessage, effectiveOptions);
                if (poolResult != null) {
                    return poolResult;
                }
                LOG.info("[LlmAgent] Pool habilitado, mas sem resposta (sem opção elegível ou todas falharam) "
                        + "— usando fluxo legado (Groq/Ollama)");
            } else {
                LOG.debug("[LlmAgent] Pool desabilitado (assistant.llm.pool.enabled=false) — fluxo legado");
            }

            LlmRouter.Provider provider = llmRouter.select(activeProvider);
            List<OllamaMessage> messages = buildMessages(effectiveSystemPrompt, history, userMessage);

            LlmRouter.LlmResponse response = llmRouter.call(
                    provider,
                    effectiveSystemPrompt,
                    messages,
                    effectiveOptions.maxTokens());
            long elapsed = System.currentTimeMillis() - start;

            if (response.isError()) {
                LOG.warnf("[LlmAgent] Resposta vazia após %dms (provider=%s)", elapsed, provider);
                return AgentResult.fallback("Desculpe, tive um probleminha. Pode repetir? 😅", provider.name());
            }

            String raw = response.text();
            LOG.infof("[LlmAgent] %s respondeu em %dms (%d chars)", provider, elapsed, raw.length());

            List<AgentAction> actions = extractActions(raw);
            String cleanText = stripActions(raw).trim();
            if (cleanText.isBlank()) cleanText = "Entendido! 😊";

            return new AgentResult(
                    cleanText,
                    actions,
                    response.provider() != null ? response.provider().name() : provider.name());

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("[LlmAgent] Falhou após %dms: %s", elapsed, e.getMessage());
            return AgentResult.fallback("Desculpe, tive um probleminha. Pode repetir? 😅", activeProvider);
        }
    }

    // ─── Extração de ações ────────────────────────────────────────────────────

    private List<AgentAction> extractActions(String text) {
        List<AgentAction> actions = new ArrayList<>();
        Matcher m = ACTION_PATTERN.matcher(text);
        while (m.find()) {
            String type = m.group(1).toUpperCase();
            Map<String, String> params = parseParams(m.group(2));
            actions.add(new AgentAction(type, params));
            LOG.debugf("[LlmAgent] Ação detectada: %s → %s", type, params);
        }
        return actions;
    }

    private Map<String, String> parseParams(String paramsStr) {
        Map<String, String> params = new HashMap<>();
        for (String pair : paramsStr.split("\\|")) {
            int eq = pair.indexOf('=');
            if (eq > 0) params.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return params;
    }

    private String stripActions(String text) {
        return ACTION_PATTERN.matcher(text).replaceAll("").trim()
                .replaceAll("\n{3,}", "\n\n");
    }

    // ─── Montagem de mensagens ────────────────────────────────────────────────

    private List<OllamaMessage> buildMessages(String systemPrompt, List<ChatMessage> history,
            String userMessage) {
        List<OllamaMessage> messages = new ArrayList<>();
        messages.add(new OllamaMessage("system", systemPrompt));
        for (ChatMessage msg : history) {
            String role = "tool".equals(msg.role) ? "user" : msg.role;
            messages.add(new OllamaMessage(role, msg.content));
        }
        if (!userMessage.isBlank()) {
            messages.add(new OllamaMessage("user", userMessage));
        }
        return messages;
    }

    private String applyRuntimeInstruction(String systemPrompt, String runtimeInstruction) {
        if (runtimeInstruction == null || runtimeInstruction.isBlank()) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n" + runtimeInstruction.trim();
    }

    // ─── Integração com o pool de provedores ──────────────────────────────────

    /**
     * Conduz a chamada pelo novo pool. Retorna {@code null} quando o pool não tem
     * capacidade/falhou, sinalizando ao chamador para usar o fluxo legado. A estrutura
     * enviada (system prompt + histórico + mensagem) é a mesma do fluxo atual — o
     * fallback entre provedores não altera contexto nem regras.
     */
    private AgentResult chatViaPool(String systemPrompt, List<ChatMessage> history,
            String userMessage, AgentChatOptions options) {
        LlmRequest req = new LlmRequest();
        req.systemPrompt = systemPrompt;
        req.historico = toPoolHistory(history);
        req.mensagemAtual = userMessage;
        req.maxTokens = options.maxTokens();
        req.tenantId = options.tenantId();
        req.conversaId = options.conversaId();

        LlmResponse resp = poolExecutor.executar(req);
        if (resp == null || resp.erro() || resp.vazia()) {
            return null;
        }
        String raw = resp.texto();
        LOG.infof("[LlmAgent] Pool respondeu (provider=POOL, %d chars)", raw.length());
        List<AgentAction> actions = extractActions(raw);
        String cleanText = stripActions(raw).trim();
        if (cleanText.isBlank()) cleanText = "Entendido! 😊";
        return new AgentResult(cleanText, actions, "POOL");
    }

    private List<LlmMessage> toPoolHistory(List<ChatMessage> history) {
        List<LlmMessage> out = new ArrayList<>();
        if (history != null) {
            for (ChatMessage msg : history) {
                String role = "tool".equals(msg.role) ? "user" : msg.role;
                out.add(new LlmMessage(role, msg.content));
            }
        }
        return out;
    }

    // ─── Tipos públicos ───────────────────────────────────────────────────────

    public record AgentResult(String text, List<AgentAction> actions, String providerUsed, boolean llmUnavailable) {

        public static AgentResult fallback(String message, String provider) {
            return new AgentResult(message, List.of(), provider, true);
        }

        // Convenience constructor for successful responses
        public AgentResult(String text, List<AgentAction> actions, String providerUsed) {
            this(text, actions, providerUsed, false);
        }

        public boolean hasAction(String type) {
            return actions.stream().anyMatch(a -> a.type().equals(type));
        }

        public AgentAction firstAction(String type) {
            return actions.stream().filter(a -> a.type().equals(type)).findFirst().orElse(null);
        }
    }

    public record AgentAction(String type, Map<String, String> params) {
        public String param(String key) {
            return params.getOrDefault(key, null);
        }
    }

    public record AgentChatOptions(Integer maxTokens, String runtimeInstruction,
            String tenantId, String conversaId) {
        public static AgentChatOptions defaultOptions() {
            return new AgentChatOptions(null, null, null, null);
        }

        /** Opções padrão que preservam o tenant/conversa para contabilização no pool. */
        public static AgentChatOptions forContext(String tenantId, String conversaId) {
            return new AgentChatOptions(null, null, tenantId, conversaId);
        }
    }
}
