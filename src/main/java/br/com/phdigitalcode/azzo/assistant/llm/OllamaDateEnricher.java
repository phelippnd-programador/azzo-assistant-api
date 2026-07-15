package br.com.phdigitalcode.azzo.assistant.llm;

import java.time.LocalDate;
import java.util.Optional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmResponse;
import br.com.phdigitalcode.azzo.assistant.llm.pool.execution.LlmPoolExecutor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Extrai datas em linguagem natural via LLM (pool de provedores). Usado como
 * fallback quando o DateTimeRegexExtractor retorna empty(). Sempre retorna
 * Optional.empty() em caso de erro/indisponibilidade — nunca lança exceção.
 */
@ApplicationScoped
public class OllamaDateEnricher {

    private static final Logger LOG = Logger.getLogger(OllamaDateEnricher.class);

    @Inject
    LlmPoolExecutor poolExecutor;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Tenta extrair uma data da mensagem usando LLM.
     * Retorna Optional.empty() se não encontrar ou se o pool falhar.
     */
    public Optional<LocalDate> enrich(String rawMessage) {
        LOG.infof("[LlmDate] Extraindo data de: '%s'", rawMessage);
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

            LlmRequest req = new LlmRequest();
            req.systemPrompt = systemPrompt;
            req.mensagemAtual = rawMessage;
            req.temperatura = 0.0;
            req.maxTokens = 30;
            req.jsonMode = true;

            LlmResponse response = poolExecutor.executar(req);
            if (response == null || response.erro() || response.vazia()) {
                return Optional.empty();
            }

            JsonNode node = objectMapper.readTree(response.texto());
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
            LOG.infof("[LlmDate] Data extraída: %s em %dms para: '%s'", parsed, elapsed, rawMessage);
            return Optional.of(parsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            LOG.warnf("[LlmDate] Date enrich falhou após %dms → fallback regex. Causa: %s", elapsed, e.getMessage());
            return Optional.empty();
        }
    }
}
