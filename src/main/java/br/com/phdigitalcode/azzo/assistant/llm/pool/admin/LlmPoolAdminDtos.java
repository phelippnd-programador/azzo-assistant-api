package br.com.phdigitalcode.azzo.assistant.llm.pool.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmModel;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmProvider;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.LlmProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** DTOs administrativos do pool. Nenhuma view expõe a chave em claro (seção 6/15). */
public final class LlmPoolAdminDtos {

  private LlmPoolAdminDtos() {}

  // ─── Provedor ───────────────────────────────────────────────────────────────

  public static class ProviderRequest {
    @NotBlank public String nome;
    @NotNull public LlmProviderType tipo;
    public String urlBase;
    public Boolean ativo;
    public Integer prioridade;
    public Integer ordemFallback;
    public String configAdaptador;
    public Integer timeoutConexaoMs;
    public Integer timeoutRespostaMs;
    public Integer quantidadeMaximaTentativas;
    public Boolean suportaStreaming;
    public Boolean suportaToolCalling;
    public Boolean suportaJsonMode;
  }

  public static class ProviderView {
    public String id;
    public String nome;
    public String tipo;
    public String urlBase;
    public boolean ativo;
    public int prioridade;
    public int ordemFallback;
    public int timeoutConexaoMs;
    public int timeoutRespostaMs;
    public int quantidadeMaximaTentativas;
    public boolean suportaStreaming;
    public boolean suportaToolCalling;
    public boolean suportaJsonMode;
    public long quantidadeChaves;
    public long quantidadeModelosAtivos;
    public String ultimaSincronizacaoModelos;
    public String dataCriacao;

    public static ProviderView de(LlmProvider p, long chaves, long modelos) {
      ProviderView v = new ProviderView();
      v.id = str(p.id);
      v.nome = p.nome;
      v.tipo = p.tipo != null ? p.tipo.name() : null;
      v.urlBase = p.urlBase;
      v.ativo = p.ativo;
      v.prioridade = p.prioridade;
      v.ordemFallback = p.ordemFallback;
      v.timeoutConexaoMs = p.timeoutConexaoMs;
      v.timeoutRespostaMs = p.timeoutRespostaMs;
      v.quantidadeMaximaTentativas = p.quantidadeMaximaTentativas;
      v.suportaStreaming = p.suportaStreaming;
      v.suportaToolCalling = p.suportaToolCalling;
      v.suportaJsonMode = p.suportaJsonMode;
      v.quantidadeChaves = chaves;
      v.quantidadeModelosAtivos = modelos;
      v.ultimaSincronizacaoModelos = str(p.ultimaSincronizacaoModelos);
      v.dataCriacao = str(p.dataCriacao);
      return v;
    }
  }

  // ─── Credencial ─────────────────────────────────────────────────────────────

  public static class CredentialRequest {
    @NotNull public UUID providerId;
    @NotBlank public String nomeIdentificacao;
    /** Chave em claro. Opcional na edição: se ausente, preserva a chave atual. */
    public String apiKey;
    public String organizacao;
    public String projeto;
    public Boolean ativo;
    public Integer peso;
    public Integer prioridade;
    public Integer limiteRequisicoesMinuto;
    public Integer limiteRequisicoesDia;
    public Long limiteTokensMinuto;
    public Long limiteTokensDia;
    public Long limiteTokensMes;
    public BigDecimal limiteCustoMensal;
  }

  /** Nunca contém a chave — apenas a máscara. */
  public static class CredentialView {
    public String id;
    public String providerId;
    public String nomeIdentificacao;
    public String chaveMascarada;
    public String organizacao;
    public String projeto;
    public boolean ativo;
    public int peso;
    public int prioridade;
    public Integer limiteRequisicoesMinuto;
    public Integer limiteRequisicoesDia;
    public Long limiteTokensMinuto;
    public Long limiteTokensDia;
    public Long limiteTokensMes;
    public BigDecimal limiteCustoMensal;
    public long totalTokensEntrada;
    public long totalTokensSaida;
    public long totalRequisicoes;
    public long totalErros;
    public String ultimoUso;
    public String bloqueadaAte;
    public String dataCriacao;

    public static CredentialView de(LlmCredential c) {
      CredentialView v = new CredentialView();
      v.id = str(c.id);
      v.providerId = str(c.providerId);
      v.nomeIdentificacao = c.nomeIdentificacao;
      v.chaveMascarada = c.apiKeyMascara; // já mascarada; a chave nunca sai daqui
      v.organizacao = c.organizacao;
      v.projeto = c.projeto;
      v.ativo = c.ativo;
      v.peso = c.peso;
      v.prioridade = c.prioridade;
      v.limiteRequisicoesMinuto = c.limiteRequisicoesMinuto;
      v.limiteRequisicoesDia = c.limiteRequisicoesDia;
      v.limiteTokensMinuto = c.limiteTokensMinuto;
      v.limiteTokensDia = c.limiteTokensDia;
      v.limiteTokensMes = c.limiteTokensMes;
      v.limiteCustoMensal = c.limiteCustoMensal;
      v.totalTokensEntrada = c.totalTokensEntrada;
      v.totalTokensSaida = c.totalTokensSaida;
      v.totalRequisicoes = c.totalRequisicoes;
      v.totalErros = c.totalErros;
      v.ultimoUso = str(c.ultimoUso);
      v.bloqueadaAte = str(c.bloqueadaAte);
      v.dataCriacao = str(c.dataCriacao);
      return v;
    }
  }

  // ─── Modelo ─────────────────────────────────────────────────────────────────

  public static class ModelRequest {
    @NotBlank public String nomeModelo;
    public String nomeExibicao;
    public Boolean ativo;
    public Integer prioridade;
    public Integer contextWindow;
    public Integer maxOutputTokens;
    public BigDecimal custoInputPorMilhao;
    public BigDecimal custoOutputPorMilhao;
    public BigDecimal custoFixoPorChamada;
    public String moeda;
    public Boolean gratuito;
    public Boolean indicadoParaAtendimento;
    public Boolean suportaStreaming;
    public Boolean suportaToolCalling;
    public Boolean suportaJsonMode;
    public Integer limiteRequisicoesMinuto;
    public Integer limiteRequisicoesDia;
  }

  public static class ModelView {
    public String id;
    public String providerId;
    public String nomeModelo;
    public String nomeExibicao;
    public boolean ativo;
    public boolean descontinuado;
    public int prioridade;
    public Integer contextWindow;
    public Integer maxOutputTokens;
    public BigDecimal custoInputPorMilhao;
    public BigDecimal custoOutputPorMilhao;
    public BigDecimal custoFixoPorChamada;
    public String moeda;
    public boolean gratuito;
    public boolean indicadoParaAtendimento;
    public boolean suportaStreaming;
    public boolean suportaToolCalling;
    public boolean suportaJsonMode;

    public static ModelView de(LlmModel m) {
      ModelView v = new ModelView();
      v.id = str(m.id);
      v.providerId = str(m.providerId);
      v.nomeModelo = m.nomeModelo;
      v.nomeExibicao = m.nomeExibicao;
      v.ativo = m.ativo;
      v.descontinuado = m.descontinuado;
      v.prioridade = m.prioridade;
      v.contextWindow = m.contextWindow;
      v.maxOutputTokens = m.maxOutputTokens;
      v.custoInputPorMilhao = m.custoInputPorMilhao;
      v.custoOutputPorMilhao = m.custoOutputPorMilhao;
      v.custoFixoPorChamada = m.custoFixoPorChamada;
      v.moeda = m.moeda;
      v.gratuito = m.gratuito;
      v.indicadoParaAtendimento = m.indicadoParaAtendimento;
      v.suportaStreaming = m.suportaStreaming;
      v.suportaToolCalling = m.suportaToolCalling;
      v.suportaJsonMode = m.suportaJsonMode;
      return v;
    }
  }

  /** Teste de conexão: chave opcional (testa uma chave nova antes de salvar). */
  public static class TestConnectionRequest {
    public UUID credentialId;
    public UUID providerId;
    public String apiKey;
  }

  private static String str(UUID id) {
    return id != null ? id.toString() : null;
  }

  private static String str(Instant i) {
    return i != null ? i.toString() : null;
  }
}
