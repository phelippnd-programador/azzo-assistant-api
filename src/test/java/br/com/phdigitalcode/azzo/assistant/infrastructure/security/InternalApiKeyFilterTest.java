package br.com.phdigitalcode.azzo.assistant.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * SEC-001/SEC-010: garante que os endpoints do assistente (/api/v1/assistant/*)
 * so respondem quando o header X-Internal-Api-Key confere. Sem/ com chave
 * invalida => 401. Endpoints publicos (health) nao passam pelo gate.
 */
class InternalApiKeyFilterTest {

  private static final String VALID_KEY = "super-secret-key";

  private InternalApiKeyFilter filter;

  @BeforeEach
  void setUp() {
    filter = new InternalApiKeyFilter();
    filter.configuredApiKey = VALID_KEY;
  }

  private ContainerRequestContext requestFor(String path, String providedKey) {
    UriInfo uriInfo = mock(UriInfo.class);
    when(uriInfo.getPath()).thenReturn(path);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    when(ctx.getUriInfo()).thenReturn(uriInfo);
    when(ctx.getHeaderString("X-Internal-Api-Key")).thenReturn(providedKey);
    return ctx;
  }

  private Response captureAbort(ContainerRequestContext ctx) {
    ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
    verify(ctx).abortWith(captor.capture());
    return captor.getValue();
  }

  @Test
  void message_semChave_retorna401() throws Exception {
    ContainerRequestContext ctx = requestFor("/api/v1/assistant/message", null);
    filter.filter(ctx);
    assertEquals(401, captureAbort(ctx).getStatus());
  }

  @Test
  void adminRetrain_semChave_retorna401() throws Exception {
    ContainerRequestContext ctx = requestFor("/api/v1/assistant/admin/retrain", null);
    filter.filter(ctx);
    assertEquals(401, captureAbort(ctx).getStatus());
  }

  @Test
  void adminMetrics_chaveInvalida_retorna401() throws Exception {
    ContainerRequestContext ctx = requestFor("/api/v1/assistant/admin/metrics", "chave-errada");
    filter.filter(ctx);
    assertEquals(401, captureAbort(ctx).getStatus());
  }

  @Test
  void message_chaveValida_passa() throws Exception {
    ContainerRequestContext ctx = requestFor("/api/v1/assistant/message", VALID_KEY);
    filter.filter(ctx);
    verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void healthPublico_naoExigeChave() throws Exception {
    ContainerRequestContext ctx = requestFor("/q/health", null);
    filter.filter(ctx);
    verify(ctx, never()).abortWith(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void adminCache_semChave_temCorpoDeErro() throws Exception {
    ContainerRequestContext ctx = requestFor("/api/v1/assistant/admin/cache", "");
    filter.filter(ctx);
    Response response = captureAbort(ctx);
    assertEquals(401, response.getStatus());
    assertNotNull(response.getEntity(), "deve retornar corpo de erro JSON");
  }
}
