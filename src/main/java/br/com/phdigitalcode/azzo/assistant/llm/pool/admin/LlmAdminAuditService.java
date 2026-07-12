package br.com.phdigitalcode.azzo.assistant.llm.pool.admin;

import java.util.UUID;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmAdminAudit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Auditoria de operações administrativas (seção 21). Registra usuário, operação,
 * provedor/credencial e campos alterados — NUNCA a chave ou partes excessivas dela.
 */
@ApplicationScoped
public class LlmAdminAuditService {

  @Transactional
  public void registrar(String usuario, String operacao, UUID providerId, UUID credentialId,
      String identificacao, String detalhesJson) {
    LlmAdminAudit a = new LlmAdminAudit();
    a.usuario = usuario;
    a.operacao = operacao;
    a.providerId = providerId;
    a.credentialId = credentialId;
    a.identificacao = identificacao;
    a.detalhes = detalhesJson == null || detalhesJson.isBlank() ? "{}" : detalhesJson;
    a.persist();
  }
}
