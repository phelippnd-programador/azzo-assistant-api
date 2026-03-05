package br.com.phdigitalcode.azzo.assistant.infrastructure.tenant;

import java.util.UUID;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Resolve o tenantId no contexto do assistant API.
 * Fontes, em ordem de prioridade:
 * 1. Override manual (definirTenantId) — usado internamente.
 * 2. Header X-Tenant-Id da requisição — enviado pelo backend principal.
 */
@RequestScoped
public class ContextoTenant {

  @Inject
  Instance<HttpHeaders> httpHeaders;

  private UUID tenantIdOverride;

  public void definirTenantId(UUID tenantId) {
    this.tenantIdOverride = tenantId;
  }

  public void limparTenantIdOverride() {
    this.tenantIdOverride = null;
  }

  public UUID obterTenantIdOuFalhar() {
    if (tenantIdOverride != null) {
      return tenantIdOverride;
    }

    String tid = obterTenantIdViaHeader();
    if (tid == null || tid.isBlank()) {
      throw new IllegalStateException("TenantId ausente: envie o header X-Tenant-Id");
    }
    return UUID.fromString(tid);
  }

  private String obterTenantIdViaHeader() {
    if (httpHeaders == null || !httpHeaders.isResolvable()) return null;
    HttpHeaders headers = httpHeaders.get();
    String value = headers.getHeaderString("X-Tenant-Id");
    if (value == null || value.isBlank()) {
      value = headers.getHeaderString("x-tenant-id");
    }
    return value;
  }
}
