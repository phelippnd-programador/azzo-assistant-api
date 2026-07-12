package br.com.phdigitalcode.azzo.assistant.llm.pool.admin;

import java.util.List;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.ProviderRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.ProviderView;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmModel;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmProvider;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

/** CRUD de provedores. */
@ApplicationScoped
public class ProviderAdminService {

  @Inject LlmAdminAuditService audit;

  public List<ProviderView> listar() {
    return LlmProvider.<LlmProvider>listAll(Sort.by("prioridade").ascending().and("nome"))
        .stream().map(this::view).toList();
  }

  public ProviderView obter(UUID id) {
    return view(buscar(id));
  }

  @Transactional
  public ProviderView criar(ProviderRequest req, String usuario) {
    LlmProvider p = new LlmProvider();
    p.tipo = req.tipo;
    aplicar(p, req);
    p.persist();
    audit.registrar(usuario, "PROVEDOR_CRIADO", p.id, null, p.nome, "{}");
    return view(p);
  }

  @Transactional
  public ProviderView atualizar(UUID id, ProviderRequest req, String usuario) {
    LlmProvider p = buscar(id);
    if (req.tipo != null) p.tipo = req.tipo;
    aplicar(p, req);
    p.persistAndFlush();
    audit.registrar(usuario, "PROVEDOR_ALTERADO", p.id, null, p.nome, "{}");
    return view(p);
  }

  @Transactional
  public void definirAtivo(UUID id, boolean ativo, String usuario) {
    LlmProvider p = buscar(id);
    p.ativo = ativo;
    p.persist();
    audit.registrar(usuario, ativo ? "PROVEDOR_ATIVADO" : "PROVEDOR_DESATIVADO", p.id, null, p.nome, "{}");
  }

  private LlmProvider buscar(UUID id) {
    LlmProvider p = LlmProvider.findById(id);
    if (p == null) throw new NotFoundException("Provedor não encontrado");
    return p;
  }

  private void aplicar(LlmProvider p, ProviderRequest req) {
    if (req.nome != null) p.nome = req.nome;
    p.urlBase = req.urlBase;
    if (req.ativo != null) p.ativo = req.ativo;
    if (req.prioridade != null) p.prioridade = req.prioridade;
    if (req.ordemFallback != null) p.ordemFallback = req.ordemFallback;
    if (req.configAdaptador != null && !req.configAdaptador.isBlank()) p.configAdaptador = req.configAdaptador;
    if (req.timeoutConexaoMs != null) p.timeoutConexaoMs = req.timeoutConexaoMs;
    if (req.timeoutRespostaMs != null) p.timeoutRespostaMs = req.timeoutRespostaMs;
    if (req.quantidadeMaximaTentativas != null) p.quantidadeMaximaTentativas = req.quantidadeMaximaTentativas;
    if (req.suportaStreaming != null) p.suportaStreaming = req.suportaStreaming;
    if (req.suportaToolCalling != null) p.suportaToolCalling = req.suportaToolCalling;
    if (req.suportaJsonMode != null) p.suportaJsonMode = req.suportaJsonMode;
  }

  private ProviderView view(LlmProvider p) {
    long chaves = LlmCredential.count("providerId = ?1 and removida = false", p.id);
    long modelos = LlmModel.count("providerId = ?1 and ativo = true and descontinuado = false", p.id);
    return ProviderView.de(p, chaves, modelos);
  }
}
