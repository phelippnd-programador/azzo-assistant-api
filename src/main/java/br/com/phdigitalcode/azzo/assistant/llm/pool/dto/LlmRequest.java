package br.com.phdigitalcode.azzo.assistant.llm.pool.dto;

import java.util.List;

/**
 * Requisição normalizada enviada ao pool, independente do provedor. Todos os
 * adaptadores recebem esta estrutura e a convertem para o formato externo — o que
 * garante que um fallback entre provedores não altere contexto nem regras (seção 12).
 */
public class LlmRequest {

  /** System prompt já montado (persona + catálogo + regras de negócio). */
  public String systemPrompt;

  /** Histórico da conversa (sem a mensagem atual). */
  public List<LlmMessage> historico = List.of();

  /** Mensagem atual do usuário. */
  public String mensagemAtual;

  /** Modelo resolvido pelo roteador para esta chamada (nome no provedor). */
  public String modelo;

  // ── Parâmetros de geração ──
  public double temperatura = 0.2;
  public Integer maxTokens;
  public boolean jsonMode = false;
  public boolean toolCalling = false;

  // ── Identificadores (para contabilização e logs seguros) ──
  public String tenantId;
  public String conversaId;
  public String mensagemId;

  /**
   * Ferramentas disponíveis (definições) quando {@link #toolCalling} — mantidas como
   * JSON opaco para não acoplar o contrato normalizado ao formato de cada provedor.
   */
  public String toolsJson;

  public LlmRequest() {}
}
