# Pool de provedores de LLM

Sistema centralizado de gerenciamento, seleção e balanceamento de provedores de IA
que decide para qual LLM cada mensagem do WhatsApp é enviada. Cadastra múltiplos
provedores e múltiplas chaves, distribui as requisições, contabiliza tokens/custo/
latência, respeita limites, faz fallback automático e nunca deixa uma falha de
provedor interromper o atendimento.

> **Status:** engine completo e integrado ao fluxo (atrás de flag). Ver
> "Limitações conhecidas" ao final para o que ainda é follow-up.

## Como ativar

O pool vem **desligado por padrão** — o fluxo legado (Groq/Ollama via `LlmRouter`)
continua funcionando sem nenhuma credencial cadastrada.

1. Configure a chave de criptografia das credenciais (AES 16/24/32 bytes, base64 ou texto):

   ```properties
   assistant.security.encryption-key=${ASSISTANT_ENCRYPTION_KEY:...}
   ```

   Use o mesmo padrão seguro do token do WhatsApp (agenda-pro `EncryptionService`).
   Sem essa chave, é impossível cadastrar credenciais (a aplicação sobe normalmente
   no fluxo legado).

2. Cadastre pelo menos um provedor, uma credencial e um modelo ativo (ver abaixo).

3. Ligue o pool:

   ```properties
   assistant.llm.pool.enabled=true
   ```

   Enquanto o pool não tiver capacidade (sem credencial/modelo elegível), cada
   chamada cai automaticamente para o fluxo legado — a transição é segura.

## Cadastrando a primeira credencial

As rotas administrativas ficam em `/api/v1/assistant/admin/llm-pool/*`
(protegidas pela chave interna `X-Internal-Api-Key`). Na prática, use a tela
**Administrador do Sistema → Provedores LLM** no app de gerenciamento, que faz
o proxy autenticado (somente `ADMINISTRADOR`).

Exemplo direto na API (Groq, OpenAI-compatible):

```bash
# 1) provedor
curl -X POST http://ASSISTANT/api/v1/assistant/admin/llm-pool/providers \
  -H 'X-Internal-Api-Key: ...' -H 'Content-Type: application/json' \
  -d '{"nome":"Groq","tipo":"OPENAI_COMPATIBLE","urlBase":"https://api.groq.com/openai/v1"}'

# 2) credencial (a chave é criptografada no servidor; a resposta traz só a máscara)
curl -X POST http://ASSISTANT/api/v1/assistant/admin/llm-pool/credentials \
  -H 'X-Internal-Api-Key: ...' -H 'Content-Type: application/json' \
  -d '{"providerId":"<id>","nomeIdentificacao":"groq-1","apiKey":"gsk_...","limiteRequisicoesMinuto":30}'

# 3) sincronizar/ativar modelos
curl -X POST http://ASSISTANT/api/v1/assistant/admin/llm-pool/providers/<id>/models/sincronizar \
  -H 'X-Internal-Api-Key: ...'
# depois ative os modelos desejados e marque custos/gratuito/indicado_para_atendimento
```

## Arquitetura

```
WhatsApp → AssistantConversationService → LlmBookingAgent
   (se pool ligado) → LlmPoolExecutor
       → LlmRoutingService (seleciona provedor/chave/modelo por score)
       → AdapterCredentialFactory (decripta a chave just-in-time)
       → LlmProviderAdapter (OpenAiCompatibleAdapter por padrão)
       → registra uso/consumo/custo → resposta normalizada
   (sem capacidade/falha total) → fluxo legado (LlmRouter)
```

- **Adaptadores** (`llm.pool.adapter`): interface `LlmProviderAdapter` + registro CDI
  (`LlmAdapterRegistry`). A maioria dos provedores (Groq, OpenRouter, Cerebras,
  Mistral, NVIDIA NIM, Fireworks, Nebius, Novita, SambaNova, Hyperbolic, Scaleway,
  AI21, Upstage, GitHub Models, Vercel AI Gateway, OpenCode Zen...) é atendida pelo
  mesmo `OpenAiCompatibleAdapter`, diferenciada por **dados** (url_base, headers,
  auth), não por código.
- **Roteamento** (`llm.pool.routing`): `LlmRoutingService` monta candidatos elegíveis
  e o `LlmRoutingScorer` os pontua pela estratégia.
- **Execução/resiliência** (`llm.pool.execution`, `.resilience`): retry com backoff+
  jitter, circuit breaker por credencial, bloqueio durável em 429, e a cadeia de
  fallback (outra chave → outro modelo → outro provedor).
- **Contabilização** (`llm.pool.consumption`, `.usage`, `.cost`): buckets atômicos
  por janela, histórico por chamada e custo estimado.

## Fórmula de roteamento (estratégia BALANCEADO)

```
score =  W_PRIORIDADE * (1 - prioridadeNorm)
       + W_GRATUITO   * (gratuito ? 1 : 0)
       + W_LATENCIA   * (1 - latenciaNorm)
       + W_LIMITE     * (1 - saturacao)
       - W_CUSTO      * custoNorm
       - W_ERRO       * taxaErro
```

Pesos e referências de normalização em `LlmRoutingScorer` (cobertos por testes).
Estratégias disponíveis: `MENOR_CUSTO`, `MENOR_LATENCIA`, `BALANCEADO` (padrão),
`PRIORIDADE_FIXA`, `ROUND_ROBIN_PONDERADO`, `GRATUITO_PRIMEIRO`.

## Concorrência

Os limites por janela (minuto/hora/dia/mês) usam a tabela `llm_consumption_bucket`
com **upsert atômico** (`INSERT ... ON CONFLICT DO UPDATE`), o que mantém a contagem
correta com múltiplas instâncias — sem contadores em memória e sem Redis (que não
existe no projeto). O bloqueio por 429 é durável (`bloqueada_ate`), válido entre
instâncias. O circuit breaker é uma otimização local (por instância).

## Segurança

- Toda API key é gravada **criptografada** (AES/GCM); nunca em texto puro.
- A API **nunca** devolve a chave — apenas a máscara (`gsk_••••4F8A`), calculada na
  gravação (a chave não é decriptada só para exibir).
- A chave é decriptada **apenas** no momento da chamada ao provedor.
- Chaves nunca entram em logs, exceções, traces ou auditoria.
- Rotas admin protegidas pela chave interna (assistant) + papel `ADMINISTRADOR`
  (gerenciamento) — enforcement no backend, não só no frontend.

## Limitações conhecidas / follow-ups

- **Matriz de testes com WireMock** (429/timeout/5xx contra um servidor fake) ainda
  não foi adicionada — a lógica pura (scoring, custo, janelas, backoff, capacidade,
  segurança da view) está coberta por testes unitários.
- **Observabilidade** (métricas Micrometer por provedor/modelo) é follow-up; hoje há
  o histórico em banco e o resumo/saúde via API.
- **`tenantId` no histórico** ainda não é propagado pelo `LlmBookingAgent` (fica nulo);
  requer passar o tenant pela cadeia de chamada.
- **Adaptadores dedicados** (Google Gemini nativo, Cohere nativo) não implementados;
  a arquitetura já está preparada (basta um bean `LlmProviderAdapter`). Enquanto isso,
  esses provedores funcionam pelos respectivos endpoints compatíveis com OpenAI.
- **Override de estratégia em runtime** é por instância (`RoutingStrategyHolder`); em
  cluster, defina `assistant.llm.pool.default-strategy` por variável de ambiente.
