package br.com.phdigitalcode.azzo.assistant.llm.pool.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.infrastructure.security.CredentialEncryptionService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.CredentialRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.CredentialView;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

/**
 * Gestão de credenciais. A chave é criptografada na gravação e nunca retorna ao
 * cliente (apenas máscara). Na edição sem nova chave, a chave atual é preservada.
 */
@ApplicationScoped
public class CredentialAdminService {

  @Inject CredentialEncryptionService encryption;
  @Inject LlmAdminAuditService audit;

  public List<CredentialView> listar(UUID providerId) {
    List<LlmCredential> creds = providerId != null
        ? LlmCredential.list("providerId = ?1 and removida = false", providerId)
        : LlmCredential.list("removida = false");
    return creds.stream().map(CredentialView::de).toList();
  }

  @Transactional
  public CredentialView criar(CredentialRequest req, String usuario) {
    exigirCriptografia();
    if (req.apiKey == null || req.apiKey.isBlank()) {
      throw new BadRequestException("A chave (apiKey) é obrigatória ao cadastrar uma credencial");
    }
    LlmProvider provider = LlmProvider.findById(req.providerId);
    if (provider == null) throw new NotFoundException("Provedor não encontrado");

    LlmCredential c = new LlmCredential();
    c.providerId = req.providerId;
    aplicar(c, req);
    definirChave(c, req.apiKey);
    c.persist();

    audit.registrar(usuario, "CREDENCIAL_CRIADA", c.providerId, c.id, c.nomeIdentificacao, "{}");
    return CredentialView.de(c);
  }

  @Transactional
  public CredentialView atualizar(UUID id, CredentialRequest req, String usuario) {
    LlmCredential c = buscar(id);
    aplicar(c, req);

    boolean chaveSubstituida = req.apiKey != null && !req.apiKey.isBlank();
    if (chaveSubstituida) {
      exigirCriptografia();
      definirChave(c, req.apiKey);
    }
    c.persistAndFlush();

    audit.registrar(usuario, chaveSubstituida ? "CREDENCIAL_SUBSTITUIDA" : "CREDENCIAL_ALTERADA",
        c.providerId, c.id, c.nomeIdentificacao, "{}");
    return CredentialView.de(c);
  }

  @Transactional
  public void definirAtivo(UUID id, boolean ativo, String usuario) {
    LlmCredential c = buscar(id);
    c.ativo = ativo;
    c.persist();
    audit.registrar(usuario, ativo ? "CREDENCIAL_ATIVADA" : "CREDENCIAL_DESATIVADA",
        c.providerId, c.id, c.nomeIdentificacao, "{}");
  }

  /** Exclusão lógica: preserva o histórico de utilização e impede novas chamadas. */
  @Transactional
  public void remover(UUID id, String usuario) {
    LlmCredential c = buscar(id);
    c.ativo = false;
    c.removida = true;
    c.persist();
    audit.registrar(usuario, "CREDENCIAL_REMOVIDA", c.providerId, c.id, c.nomeIdentificacao, "{}");
  }

  @Transactional
  public void desbloquear(UUID id, String usuario) {
    LlmCredential c = buscar(id);
    c.bloqueadaAte = null;
    c.persist();
    audit.registrar(usuario, "CREDENCIAL_DESBLOQUEADA", c.providerId, c.id, c.nomeIdentificacao, "{}");
  }

  // ─── Internos ──────────────────────────────────────────────────────────────

  private LlmCredential buscar(UUID id) {
    LlmCredential c = LlmCredential.findById(id);
    if (c == null || c.removida) throw new NotFoundException("Credencial não encontrada");
    return c;
  }

  private void definirChave(LlmCredential c, String apiKeyPlain) {
    String plain = apiKeyPlain.trim();
    c.apiKeyCriptografada = encryption.encrypt(plain);
    c.apiKeyMascara = encryption.mask(plain);
    // A referência em claro sai de escopo imediatamente; nunca é logada.
  }

  private void aplicar(LlmCredential c, CredentialRequest req) {
    if (req.nomeIdentificacao != null) c.nomeIdentificacao = req.nomeIdentificacao;
    c.organizacao = req.organizacao;
    c.projeto = req.projeto;
    if (req.ativo != null) c.ativo = req.ativo;
    if (req.peso != null) c.peso = req.peso;
    if (req.prioridade != null) c.prioridade = req.prioridade;
    c.limiteRequisicoesMinuto = req.limiteRequisicoesMinuto;
    c.limiteRequisicoesDia = req.limiteRequisicoesDia;
    c.limiteTokensMinuto = req.limiteTokensMinuto;
    c.limiteTokensDia = req.limiteTokensDia;
    c.limiteTokensMes = req.limiteTokensMes;
    c.limiteCustoMensal = req.limiteCustoMensal;
  }

  private void exigirCriptografia() {
    if (!encryption.isConfigured()) {
      throw new BadRequestException(
          "Criptografia não configurada (assistant.security.encryption-key) — não é possível gravar credenciais");
    }
  }

  /** Uso interno pelo teste de conexão. */
  public LlmCredential entidade(UUID id) {
    return buscar(id);
  }

  public Instant agora() {
    return Instant.now();
  }
}
