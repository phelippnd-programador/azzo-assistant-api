package br.com.phdigitalcode.azzo.assistant.llm.pool.admin;

import br.com.phdigitalcode.azzo.assistant.infrastructure.security.CredentialEncryptionService;
import br.com.phdigitalcode.azzo.assistant.llm.pool.adapter.AdapterCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.adapter.LlmAdapterRegistry;
import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.TestConnectionRequest;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmProvider;
import br.com.phdigitalcode.azzo.assistant.llm.pool.dto.ProviderHealthResult;
import br.com.phdigitalcode.azzo.assistant.llm.pool.execution.AdapterCredentialFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

/**
 * Teste seguro de conexão (seção 16). Valida autenticação e lista modelos quando
 * suportado, medindo latência. Nunca registra a chave. Aceita testar uma credencial
 * já salva ou uma chave nova ainda não persistida.
 */
@ApplicationScoped
public class PoolTestConnectionService {

  @Inject AdapterCredentialFactory credentialFactory;
  @Inject LlmAdapterRegistry registry;
  @Inject CredentialEncryptionService encryption;
  @Inject LlmAdminAuditService audit;

  public ProviderHealthResult testar(TestConnectionRequest req, String usuario) {
    LlmProvider provider;
    AdapterCredential ac;

    if (req.credentialId != null) {
      LlmCredential c = LlmCredential.findById(req.credentialId);
      if (c == null || c.removida) throw new NotFoundException("Credencial não encontrada");
      provider = LlmProvider.findById(c.providerId);
      if (provider == null) throw new NotFoundException("Provedor não encontrado");
      ac = credentialFactory.build(provider, c);
    } else if (req.providerId != null && req.apiKey != null && !req.apiKey.isBlank()) {
      provider = LlmProvider.findById(req.providerId);
      if (provider == null) throw new NotFoundException("Provedor não encontrado");
      ac = credentialFactory.buildComChave(provider, req.apiKey.trim(), null, null);
    } else {
      throw new BadRequestException("Informe credentialId ou (providerId + apiKey) para testar");
    }

    ProviderHealthResult resultado = registry.resolver(provider.tipo).testarConexao(ac);
    // Auditoria sem qualquer parte da chave.
    audit.registrar(usuario, "TESTE_CONEXAO", provider.id, req.credentialId, provider.nome,
        "{\"sucesso\":" + resultado.sucesso() + "}");
    return resultado;
  }
}
