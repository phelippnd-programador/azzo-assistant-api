package br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums;

/**
 * Família de adaptador que atende um provedor de LLM.
 *
 * <p>A diferenciação entre a maioria dos provedores (OpenRouter, Groq, Cerebras, Mistral,
 * NVIDIA NIM, Fireworks, Nebius, Novita, SambaNova, Hyperbolic, Scaleway, AI21, Upstage,
 * OpenCode Zen, Vercel AI Gateway, GitHub Models, Baseten, Inference.net, Alibaba...) é
 * feita por <b>dados</b> (url_base, esquema de autenticação, headers, modelo) e não por
 * código: todos são atendidos pelo mesmo adaptador {@code OPENAI_COMPATIBLE}. Apenas
 * provedores com API realmente distinta ganham um tipo/adaptador próprio.
 */
public enum LlmProviderType {

  /** Qualquer API compatível com OpenAI (chat/completions). Configurável por url_base/headers. */
  OPENAI_COMPATIBLE(true),

  /** LLM local (llama.cpp / Ollama) — expõe API compatível com OpenAI. */
  OLLAMA(true),

  /** Hugging Face Inference Providers — roteia via endpoint compatível com OpenAI. */
  HUGGINGFACE(true),

  /** Cloudflare Workers AI — possui endpoint compatível com OpenAI. */
  CLOUDFLARE_WORKERS_AI(true),

  /** Google AI Studio (Gemini) — API nativa; adaptador dedicado (fase futura). */
  GOOGLE_AI_STUDIO(false),

  /** Cohere — API nativa; adaptador dedicado (fase futura). */
  COHERE(false),

  /** Provedor genérico configurado manualmente sem garantia de compatibilidade. */
  CUSTOM(false);

  private final boolean openAiCompatible;

  LlmProviderType(boolean openAiCompatible) {
    this.openAiCompatible = openAiCompatible;
  }

  /** Se o provedor pode ser atendido pelo adaptador base OpenAI-compatible. */
  public boolean isOpenAiCompatible() {
    return openAiCompatible;
  }
}
