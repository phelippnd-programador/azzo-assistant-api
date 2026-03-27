package br.com.phdigitalcode.azzo.assistant.llm;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import br.com.phdigitalcode.azzo.assistant.infrastructure.client.AgendaProInternalClient;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.HorarioFuncionamentoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ProfissionalDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.SalonInfoDto;
import br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto.ServicoDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * Constrói o system prompt dinâmico do agente LLM com todos os dados do salão
 * (serviços, profissionais, preços) obtidos via API.
 *
 * Os serviços são referenciados como S1, S2... e profissionais como P1, P2...
 * para economizar tokens no histórico. Os UUIDs reais são mantidos internamente
 * para resolução quando o LLM emitir uma action token.
 *
 * O prompt é cacheado por tenant por 10 minutos.
 */
@ApplicationScoped
public class AgentSystemPromptBuilder {

    private static final Logger LOG = Logger.getLogger(AgentSystemPromptBuilder.class);
    private static final long CACHE_TTL_MS = 3 * 60 * 1000L; // 3 minutos (fallback de segurança)

    @Inject
    @RestClient
    AgendaProInternalClient agendaProClient;

    private final Map<String, CachedContext> cache = new ConcurrentHashMap<>();

    // ─── API pública ──────────────────────────────────────────────────────────

    public String build(String tenantId) {
        CachedContext ctx = getOrBuild(tenantId);
        return ctx.systemPrompt;
    }

    public Optional<UUID> resolveServiceId(String tenantId, String alias) {
        CachedContext ctx = getOrBuild(tenantId);
        String id = ctx.serviceAliasToId.get(alias.toUpperCase());
        if (id == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Optional<UUID> resolveProfessionalId(String tenantId, String alias) {
        CachedContext ctx = getOrBuild(tenantId);
        String id = ctx.professionalAliasToId.get(alias.toUpperCase());
        if (id == null) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Invalida o cache de um tenant (ex.: após atualização de serviços). */
    public void invalidate(String tenantId) {
        cache.remove(tenantId);
    }

    // ─── Internos ─────────────────────────────────────────────────────────────

    private CachedContext getOrBuild(String tenantId) {
        CachedContext existing = cache.get(tenantId);
        if (existing != null && !existing.isExpired()) {
            return existing;
        }
        CachedContext fresh = buildContext(tenantId);
        cache.put(tenantId, fresh);
        return fresh;
    }

    private CachedContext buildContext(String tenantId) {
        String salonName = fetchSalonName(tenantId);
        List<ServicoDto> services = fetchServices(tenantId);
        List<ProfissionalDto> professionals = fetchProfessionals(tenantId);
        List<HorarioFuncionamentoDto> schedule = fetchSchedule(tenantId);

        Map<String, String> serviceAliasToId = new LinkedHashMap<>();
        Map<String, String> professionalAliasToId = new LinkedHashMap<>();

        StringBuilder sb = new StringBuilder();

        // ── Persona ───────────────────────────────────────────────────────────
        LocalDate today        = LocalDate.now();
        LocalDate tomorrow     = today.plusDays(1);
        LocalDate afterTomorrow = today.plusDays(2);
        Locale ptBR            = Locale.forLanguageTag("pt-BR");

        String todayFmt    = today.getDayOfWeek().getDisplayName(TextStyle.FULL, ptBR);
        String tomorrowFmt = tomorrow.getDayOfWeek().getDisplayName(TextStyle.FULL, ptBR);

        sb.append("Você é Azza, atendente do ").append(salonName).append(".\n");
        sb.append("Trabalha no salão há anos — conhece cada serviço, cada profissional e cada detalhe do atendimento.\n");
        sb.append("Hoje é ").append(today).append(" (").append(todayFmt).append(").");
        sb.append(" Amanhã = ").append(tomorrow).append(" (").append(tomorrowFmt).append(").");
        sb.append(" Depois de amanhã = ").append(afterTomorrow).append(".\n\n");

        sb.append("""
COMO VOCÊ FALA:
- Informal, como qualquer atendente de salão no WhatsApp: "oi!", "claro!", "que ótimo!", "deixa eu ver aqui pra você"
- Natural, sem soar como robô nem como propaganda
- Quando não tem certeza: "Deixa eu checar isso rapidinho" — NUNCA inventa
- Máximo 4 linhas por resposta. No máximo 2 emojis.

EXEMPLOS DE TOM:
✗ "Prezada cliente, como posso auxiliá-la hoje?"
✓ "Oi! Tudo bem? Me conta o que você quer fazer hoje 😊"

✗ "Não possuo essa informação no momento."
✓ "Deixa eu verificar isso rapidinho pra você!"

✗ "O serviço X possui valor de R$50,00 conforme tabela."
✓ "O corte aqui tá R$50, e já inclui a lavagem!"

REGRA NÚMERO UM — NÃO NEGOCIÁVEL:
Você é um terminal de dados. Só repassa o que está na lista abaixo.
ANTES DE CADA RESPOSTA, verifique: "este serviço/preço está na seção O QUE O SALÃO FAZ?"
→ SIM: pode falar. → NÃO: não existe, não mencione, não sugira.

PROIBIDO — serviço inventado:
✗ "Aproveite e faça uma hidratação também!" (se hidratação não está no catálogo)
✗ "A gente também faz progressiva!" (se não está listado)
✗ Qualquer preço diferente do listado abaixo

CORRETO:
✓ Só mencionar serviços e profissionais presentes na lista abaixo
✓ Se cliente pedir serviço inexistente: "Esse serviço não temos, mas posso te contar o que oferecemos!"

REGRA — DATAS RETROATIVAS:
NUNCA agende para uma data que já passou. Hoje é sempre a data informada no início deste prompt.
Se o cliente pedir uma data anterior a hoje, recuse com naturalidade:
✓ "Essa data já passou! Me fala uma data a partir de hoje que marco pra você 😊"
✗ NUNCA emita [CRIAR_AGENDAMENTO] com date anterior à data de hoje.

""");


        // Serviços
        sb.append("=== O QUE O SALÃO FAZ ===\n");
        int si = 1;
        for (ServicoDto s : services.stream().limit(15).toList()) {
            String alias = "S" + si++;
            serviceAliasToId.put(alias, s.id);
            sb.append("[").append(alias).append("] ").append(s.name);
            if (s.price > 0) sb.append(" — R$").append(String.format(Locale.ROOT, "%.0f", s.price));
            if (s.duration > 0) sb.append(" | ").append(formatDuration(s.duration));
            sb.append("\n");
            if (s.description != null && !s.description.isBlank()) {
                sb.append("   ").append(s.description.trim()).append("\n");
            }
        }

        // Profissionais
        sb.append("\n=== EQUIPE ===\n");
        int pi = 1;
        for (ProfissionalDto p : professionals.stream().limit(15).toList()) {
            String alias = "P" + pi++;
            professionalAliasToId.put(alias, p.id);
            sb.append("[").append(alias).append("] ").append(p.name);
            if (p.specialtiesDetailed != null && !p.specialtiesDetailed.isEmpty()) {
                String specs = p.specialtiesDetailed.stream()
                        .map(sp -> sp.name)
                        .collect(java.util.stream.Collectors.joining(", "));
                sb.append(" — ").append(specs);
            }
            sb.append("\n");
        }

        // Horários de funcionamento
        sb.append("\n=== QUANDO O SALÃO ABRE ===\n");
        if (schedule.isEmpty()) {
            sb.append("Horários não configurados — oriente o cliente a ligar para confirmar.\n");
        } else {
            for (HorarioFuncionamentoDto h : schedule) {
                String dayLabel = normalizeDayLabel(h.day);
                if (!h.enabled) {
                    sb.append(dayLabel).append(": FECHADO\n");
                } else {
                    sb.append(dayLabel).append(": ").append(h.open).append(" às ").append(h.close).append("\n");
                }
            }
            sb.append("→ SOMENTE mencione dia fechado se o cliente pedir explicitamente um agendamento em um dia que aparece como FECHADO acima.\n");
            sb.append("→ NÃO mencione dias fechados proativamente, NÃO mencione feriados — não existe controle de feriados neste sistema.\n");
            sb.append("→ Se perguntarem sobre horários, responda APENAS com os dados acima, sem adicionar informações.\n");
        }

        // Fluxo e ações
        sb.append("""

=== PARA FAZER UM AGENDAMENTO ===
Colete naturalmente (não precisa ser na ordem exata, só garanta que tem tudo):
nome do cliente → serviço → profissional (se não tiver preferência, sugira P1) → data → período (manhã/tarde/noite) → horário → confirmação do cliente.

=== AÇÕES DO SISTEMA (use quando necessário) ===
Para ver horários livres — coloque EXATAMENTE no final da mensagem, sem nada depois:
[CONSULTAR_HORARIOS:prof=P1|date=YYYY-MM-DD|svc=S1]

CONFIRMAÇÃO DE AGENDAMENTO — REGRA CRÍTICA:
1. Quando tiver todos os dados (serviço, profissional, data, horário, nome), apresente o resumo e pergunte "Confirma?"
2. Quando o cliente responder SIM (ou "ok", "pode", "confirmo", "vai", "bora", "fecha", "tá bom" etc.):
   → OBRIGATÓRIO: emita [CRIAR_AGENDAMENTO:...] NO FINAL da sua resposta
   → NUNCA diga "agendamento feito!" ou "marquei pra você!" sem ter emitido o token — o sistema não criará nada
   → O token É o comando de criação: sem ele, nada acontece no sistema
[CRIAR_AGENDAMENTO:svc=S1|prof=P1|date=YYYY-MM-DD|time=HH:MM|customer=NomeCliente]

Para cancelar um agendamento existente:
[CANCELAR_AGENDAMENTO:appointment_id=UUID]

=== REGRAS QUE NUNCA QUEBRAM ===
- Os preços e serviços listados acima são os únicos que existem — NUNCA invente ou altere valores.
- NUNCA mencione feriados — o sistema não tem controle de feriados.
- Se o cliente perguntar sobre algo fora do salão: "Sou especialista em beleza, posso ajudar com agendamentos! 💅"
- Datas relativas ("amanhã", "sexta que vem"): calcule a partir de hoje.
- Os aliases S1, P1 etc. são só para as ações do sistema — NUNCA mencione para o cliente.
""");

        LOG.infof("[AgentPrompt] Prompt construído para tenant=%s: %d serviços, %d profissionais",
                tenantId, serviceAliasToId.size(), professionalAliasToId.size());

        return new CachedContext(sb.toString(), serviceAliasToId, professionalAliasToId);
    }

    private String fetchSalonName(String tenantId) {
        try {
            SalonInfoDto info = agendaProClient.obterInfoTenant(tenantId);
            if (info != null && info.name != null && !info.name.isBlank()) {
                return info.name.trim();
            }
        } catch (RuntimeException e) {
            LOG.warnf("[AgentPrompt] Falha ao buscar info do salão: %s", e.getMessage());
        }
        return "Beleza";
    }

    private List<ServicoDto> fetchServices(String tenantId) {
        try {
            return agendaProClient.listarServicos(tenantId).stream()
                    .filter(s -> s.isActive)
                    .toList();
        } catch (RuntimeException e) {
            LOG.warnf("[AgentPrompt] Falha ao buscar serviços: %s", e.getMessage());
            return List.of();
        }
    }

    private List<ProfissionalDto> fetchProfessionals(String tenantId) {
        try {
            return agendaProClient.listarProfissionais(tenantId, null).stream()
                    .filter(p -> p.isActive)
                    .toList();
        } catch (RuntimeException e) {
            LOG.warnf("[AgentPrompt] Falha ao buscar profissionais: %s", e.getMessage());
            return List.of();
        }
    }

    private List<HorarioFuncionamentoDto> fetchSchedule(String tenantId) {
        try {
            List<HorarioFuncionamentoDto> list = agendaProClient.listarHorariosFuncionamento(tenantId);
            return list != null ? list : List.of();
        } catch (RuntimeException e) {
            LOG.warnf("[AgentPrompt] Falha ao buscar horários de funcionamento: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Normaliza o nome do dia vindo do banco ("Terca-feira", "Sabado"...)
     * para a forma acentuada legível pelo LLM ("Terça-feira", "Sábado"...).
     */
    private String normalizeDayLabel(String day) {
        if (day == null) return "?";
        return switch (day.trim().toLowerCase()) {
            case "segunda-feira", "segunda" -> "Segunda-feira";
            case "terca-feira", "terca"     -> "Terça-feira";
            case "quarta-feira", "quarta"   -> "Quarta-feira";
            case "quinta-feira", "quinta"   -> "Quinta-feira";
            case "sexta-feira", "sexta"     -> "Sexta-feira";
            case "sabado", "sábado"         -> "Sábado";
            case "domingo"                  -> "Domingo";
            default                         -> day;
        };
    }

    private String formatDuration(int minutes) {
        if (minutes < 60) return minutes + "min";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + "h" : h + "h" + m + "min";
    }

    // ─── Cache ────────────────────────────────────────────────────────────────

    private static class CachedContext {
        final String systemPrompt;
        final Map<String, String> serviceAliasToId;
        final Map<String, String> professionalAliasToId;
        final Instant expiresAt;

        CachedContext(String systemPrompt, Map<String, String> serviceAliasToId,
                Map<String, String> professionalAliasToId) {
            this.systemPrompt = systemPrompt;
            this.serviceAliasToId = serviceAliasToId;
            this.professionalAliasToId = professionalAliasToId;
            this.expiresAt = Instant.now().plusMillis(CACHE_TTL_MS);
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
