package br.com.phdigitalcode.azzo.assistant.infrastructure.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Autenticacao service-to-service do assistant-api.
 *
 * Protege os endpoints sensiveis do assistente exigindo o header
 * X-Internal-Api-Key igual a app.internal.api-key. Cobre:
 *   - /api/v1/assistant/message   (evita spoofing de tenant/usuario via headers)
 *   - /api/v1/assistant/admin/*   (retrain, metrics, cache, seeds)
 *
 * A comparacao e feita em tempo constante para evitar timing attacks.
 * Endpoints publicos de saude (/q/health) e verificacao nao passam por aqui.
 */
@Provider
@ApplicationScoped
public class InternalApiKeyFilter implements ContainerRequestFilter {

  private static final Logger LOG = Logger.getLogger(InternalApiKeyFilter.class);
  private static final String PROTECTED_PREFIX = "/api/v1/assistant";
  private static final String HEADER_NAME = "X-Internal-Api-Key";

  @ConfigProperty(name = "app.internal.api-key", defaultValue = "changeme-dev")
  String configuredApiKey;

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    String path = normalize(requestContext.getUriInfo().getPath());
    if (!path.startsWith(PROTECTED_PREFIX)) {
      return;
    }

    String providedKey = requestContext.getHeaderString(HEADER_NAME);
    if (providedKey == null || providedKey.isBlank()) {
      LOG.warnf("assistant.internal.rejected header=%s_ausente path=%s", HEADER_NAME, path);
      requestContext.abortWith(unauthorized("Header " + HEADER_NAME + " obrigatorio"));
      return;
    }

    if (!constantTimeEquals(configuredApiKey, providedKey.trim())) {
      LOG.warnf("assistant.internal.rejected reason=chave_invalida path=%s", path);
      requestContext.abortWith(unauthorized("Chave interna invalida"));
    }
  }

  private static String normalize(String path) {
    if (path == null) return "";
    return path.startsWith("/") ? path : "/" + path;
  }

  private static Response unauthorized(String message) {
    return Response.status(Response.Status.UNAUTHORIZED)
        .entity("{\"error\":\"" + message + "\"}")
        .build();
  }

  private static boolean constantTimeEquals(String expected, String provided) {
    if (expected == null || provided == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        provided.getBytes(StandardCharsets.UTF_8));
  }
}
