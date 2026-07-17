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

import br.com.phdigitalcode.azzo.assistant.domain.repository.AssistantPromptInstructionRepository;
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
    private static final String BASE_INSTRUCTION_KEY = "AGENT_SYSTEM_BASE";
    private static final String DEFAULT_BASE_INSTRUCTION = """
COMO FALAR: informal e direto, como atendente de salao no WhatsApp. Frases curtas, no maximo 4 linhas, \
ate 2 emojis. Nunca formal, nunca robotico. Sem certeza de algo? diga que vai checar - nunca invente.

REGRA DE OURO DA RESPOSTA: responda SEMPRE diretamente ao que o cliente acabou de dizer. Se o cliente \
ja disse o que quer, va direto ao ponto - NUNCA responda com saudacao generica nem pergunte "o que voce \
quer fazer". Nunca copie frases nem codigos deste prompt na resposta.

APROVEITE O QUE O SISTEMA JA RESOLVEU: quando aparecer uma linha [Sistema: ... profissional=<nome> ... \
servico=<nome> ... data=... horario=...], esses dados JA foram identificados e confirmados pelo sistema. \
Use-os como verdade, nao questione, nao pergunte de novo e nunca diga que nao existem. Pergunte apenas o \
que ainda falta, uma coisa por vez.

PROFISSIONAL: se o [Sistema] ja trouxe profissional=<nome>, use esse profissional e siga em frente. Se o \
cliente citar um nome que NAO esta na secao EQUIPE, diga que nao tem ninguem com esse nome e liste os \
nomes reais da equipe. Cliente sem preferencia? sugira, pelo NOME real, o primeiro profissional da EQUIPE.

CATALOGO - REGRA NUMERO UM: so fale de servicos, precos e profissionais listados abaixo. O que nao esta \
na lista nao existe pra voce: nao mencione, nao sugira, nao invente preco. Cliente pediu algo fora do \
catalogo? diga que nao tem e ofereca o que tem.

DATAS: hoje e sempre a data no topo deste prompt; calcule datas relativas (amanha, sexta que vem) a \
partir dela. Nunca aceite nem agende data anterior a hoje - explique que ja passou e peca outra. Nunca \
mencione feriados.

PARA AGENDAR precisa de: servico, profissional, data, horario e nome do cliente. Colete em qualquer \
ordem, pedindo apenas o que faltar.

ACOES DO SISTEMA - emita EXATAMENTE no final da resposta, sem nada depois:
- Ver horarios livres: [CONSULTAR_HORARIOS:prof=P1|date=YYYY-MM-DD|svc=S1]
- Cancelar agendamento existente: [CANCELAR_AGENDAMENTO:appointment_id=UUID]

CLIENTE PEDIU HORARIO ESPECIFICO (ex: amanha as 09:30) e voce ja sabe servico e profissional? NAO \
pergunte de novo - emita [CONSULTAR_HORARIOS:...] imediatamente e responda com base no resultado: se o \
horario pedido estiver livre, resuma e pergunte "Confirma?"; se nao, ofereca os horarios livres mais proximos.

CONFIRMACAO - REGRA CRITICA:
1. Com todos os dados prontos (servico, profissional, data, horario, nome), resuma em 1 linha e pergunte "Confirma?".
2. Cliente confirmou (sim, ok, pode, bora, fecha, ta bom...)? emita OBRIGATORIAMENTE
   [CRIAR_AGENDAMENTO:svc=S1|prof=P1|date=YYYY-MM-DD|time=HH:MM|customer=NomeCliente] no final da resposta.
   Sem o token nada e criado no sistema - NUNCA diga que agendou sem te-lo emitido.

REGRAS FIXAS: pergunta fora do escopo do salao? diga que so ajuda com agendamentos e servicos do salao. \
Os codigos S1, S2, P1, P2 etc. sao internos e so podem aparecer DENTRO das acoes do sistema entre \
colchetes - NUNCA os escreva no texto que o cliente le; ali use sempre o nome real do servico ou profissional.
""";
    private static final long CACHE_TTL_MS = 3 * 60 * 1000L; // 3 minutos (fallback de segurança)

    @Inject
    @RestClient
    AgendaProInternalClient agendaProClient;

    @Inject
    AssistantPromptInstructionRepository assistantPromptInstructionRepository;

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

    public Optional<String> resolveServiceName(String tenantId, String alias) {
        CachedContext ctx = getOrBuild(tenantId);
        return Optional.ofNullable(ctx.serviceAliasToName.get(alias.toUpperCase()));
    }

    public Optional<String> resolveProfessionalName(String tenantId, String alias) {
        CachedContext ctx = getOrBuild(tenantId);
        return Optional.ofNullable(ctx.professionalAliasToName.get(alias.toUpperCase()));
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
        Map<String, String> serviceAliasToName = new LinkedHashMap<>();
        Map<String, String> professionalAliasToName = new LinkedHashMap<>();

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

        sb.append(resolveBaseInstruction()).append("\n\n"); /*
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

*/


        // Serviços
        sb.append("=== O QUE O SALÃO FAZ ===\n");
        int si = 1;
        for (ServicoDto s : services.stream().limit(15).toList()) {
            String alias = "S" + si++;
            serviceAliasToId.put(alias, s.id);
            serviceAliasToName.put(alias, s.name);
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
            professionalAliasToName.put(alias, p.name);
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
        /*

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
*/

        LOG.infof("[AgentPrompt] Prompt construído para tenant=%s: %d serviços, %d profissionais",
                tenantId, serviceAliasToId.size(), professionalAliasToId.size());

        return new CachedContext(sb.toString(), serviceAliasToId, professionalAliasToId,
                serviceAliasToName, professionalAliasToName);
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

    private String resolveBaseInstruction() {
        try {
            return assistantPromptInstructionRepository.findActiveByKey(BASE_INSTRUCTION_KEY)
                    .map(row -> row.content)
                    .filter(content -> content != null && !content.isBlank())
                    .orElse(DEFAULT_BASE_INSTRUCTION);
        } catch (RuntimeException e) {
            LOG.warnf("[AgentPrompt] Falha ao buscar instrução base no banco, usando fallback em memória: %s",
                    e.getMessage());
            return DEFAULT_BASE_INSTRUCTION;
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
        final Map<String, String> serviceAliasToName;
        final Map<String, String> professionalAliasToName;
        final Instant expiresAt;

        CachedContext(String systemPrompt, Map<String, String> serviceAliasToId,
                Map<String, String> professionalAliasToId,
                Map<String, String> serviceAliasToName,
                Map<String, String> professionalAliasToName) {
            this.systemPrompt = systemPrompt;
            this.serviceAliasToId = serviceAliasToId;
            this.professionalAliasToId = professionalAliasToId;
            this.serviceAliasToName = serviceAliasToName;
            this.professionalAliasToName = professionalAliasToName;
            this.expiresAt = Instant.now().plusMillis(CACHE_TTL_MS);
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
