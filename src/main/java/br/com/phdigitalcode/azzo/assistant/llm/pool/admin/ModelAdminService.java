package br.com.phdigitalcode.azzo.assistant.llm.pool.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.adapter.AdapterCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.adapter.LlmAdapterRegistry;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.ModelRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.ModelView;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmModel;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmProvider;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.LlmModelInfo;
import br.com.phdigitalcode.azzo.assistant.llm.pool.execution.AdapterCredentialFactory;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

/** CRUD de modelos e sincronização com o provedor (seção 17). */
@ApplicationScoped
public class ModelAdminService {

  private static final Logger LOG = Logger.getLogger(ModelAdminService.class);

  @Inject LlmAdapterRegistry registry;
  @Inject AdapterCredentialFactory credentialFactory;
  @Inject LlmAdminAuditService audit;

  public List<ModelView> listar(UUID providerId) {
    List<LlmModel> modelos = LlmModel.list("providerId = ?1", Sort.by("prioridade").ascending().and("nomeModelo"), providerId);
    return modelos.stream().map(ModelView::de).toList();
  }

  @Transactional
  public ModelView criar(UUID providerId, ModelRequest req, String usuario) {
    LlmProvider provider = LlmProvider.findById(providerId);
    if (provider == null) throw new NotFoundException("Provedor não encontrado");

    LlmModel m = new LlmModel();
    m.providerId = providerId;
    m.nomeModelo = req.nomeModelo;
    aplicar(m, req);
    m.persist();
    audit.registrar(usuario, "MODELO_CRIADO", providerId, null, m.nomeModelo, "{}");
    return ModelView.de(m);
  }

  @Transactional
  public ModelView atualizar(UUID id, ModelRequest req, String usuario) {
    LlmModel m = LlmModel.findById(id);
    if (m == null) throw new NotFoundException("Modelo não encontrado");
    aplicar(m, req);
    m.persistAndFlush();
    audit.registrar(usuario, "MODELO_ALTERADO", m.providerId, null, m.nomeModelo, "{}");
    return ModelView.de(m);
  }

  @Transactional
  public void definirAtivo(UUID id, boolean ativo, String usuario) {
    LlmModel m = LlmModel.findById(id);
    if (m == null) throw new NotFoundException("Modelo não encontrado");
    m.ativo = ativo;
    m.persist();
    audit.registrar(usuario, ativo ? "MODELO_ATIVADO" : "MODELO_DESATIVADO", m.providerId, null, m.nomeModelo, "{}");
  }

  /**
   * Sincroniza a lista de modelos com o provedor. Não apaga configurações: modelos
   * novos entram desativados (ativar sob seleção), os que sumiram viram descontinuados,
   * e registra a data da sincronização.
   */
  @Transactional
  public List<ModelView> sincronizar(UUID providerId, String usuario) {
    LlmProvider provider = LlmProvider.findById(providerId);
    if (provider == null) throw new NotFoundException("Provedor não encontrado");

    LlmCredential credencial = LlmCredential.find(
        "providerId = ?1 and ativo = true and removida = false", providerId).firstResult();
    if (credencial == null) {
      throw new BadRequestException("Nenhuma credencial ativa para consultar os modelos deste provedor");
    }

    List<LlmModelInfo> remotos;
    try {
      AdapterCredential ac = credentialFactory.build(provider, credencial);
      remotos = registry.resolver(provider.tipo).listarModelos(ac);
    } catch (RuntimeException e) {
      LOG.warnf("[ModelSync] Falha ao listar modelos de provider=%s: %s", provider.nome, e.getMessage());
      throw new BadRequestException("Não foi possível consultar os modelos do provedor");
    }

    List<LlmModel> existentes = LlmModel.list("providerId", providerId);
    java.util.Set<String> nomesRemotos = new java.util.HashSet<>();
    for (LlmModelInfo info : remotos) {
      nomesRemotos.add(info.id());
      LlmModel existente = existentes.stream()
          .filter(m -> m.nomeModelo.equalsIgnoreCase(info.id()))
          .findFirst().orElse(null);
      if (existente == null) {
        LlmModel novo = new LlmModel();
        novo.providerId = providerId;
        novo.nomeModelo = info.id();
        novo.nomeExibicao = info.nomeExibicao();
        novo.ativo = false; // ativar somente sob seleção do admin
        novo.contextWindow = info.contextWindow();
        novo.persist();
      } else {
        existente.descontinuado = false; // reapareceu
        existente.persist();
      }
    }
    // Modelos que sumiram da lista remota → descontinuados (sem apagar a config).
    for (LlmModel m : existentes) {
      if (!nomesRemotos.contains(m.nomeModelo)) {
        m.descontinuado = true;
        m.persist();
      }
    }

    provider.ultimaSincronizacaoModelos = Instant.now();
    provider.persist();
    audit.registrar(usuario, "MODELOS_SINCRONIZADOS", providerId, null, provider.nome,
        "{\"encontrados\":" + remotos.size() + "}");
    return listar(providerId);
  }

  private void aplicar(LlmModel m, ModelRequest req) {
    if (req.nomeExibicao != null) m.nomeExibicao = req.nomeExibicao;
    if (req.ativo != null) m.ativo = req.ativo;
    if (req.prioridade != null) m.prioridade = req.prioridade;
    m.contextWindow = req.contextWindow;
    m.maxOutputTokens = req.maxOutputTokens;
    if (req.custoInputPorMilhao != null) m.custoInputPorMilhao = req.custoInputPorMilhao;
    if (req.custoOutputPorMilhao != null) m.custoOutputPorMilhao = req.custoOutputPorMilhao;
    if (req.custoFixoPorChamada != null) m.custoFixoPorChamada = req.custoFixoPorChamada;
    if (req.moeda != null) m.moeda = req.moeda;
    if (req.gratuito != null) m.gratuito = req.gratuito;
    if (req.indicadoParaAtendimento != null) m.indicadoParaAtendimento = req.indicadoParaAtendimento;
    if (req.suportaStreaming != null) m.suportaStreaming = req.suportaStreaming;
    if (req.suportaToolCalling != null) m.suportaToolCalling = req.suportaToolCalling;
    if (req.suportaJsonMode != null) m.suportaJsonMode = req.suportaJsonMode;
    m.limiteRequisicoesMinuto = req.limiteRequisicoesMinuto;
    m.limiteRequisicoesDia = req.limiteRequisicoesDia;
    m.limiteTokensMinuto = req.limiteTokensMinuto;
    m.limiteTokensDia = req.limiteTokensDia;
  }
}
