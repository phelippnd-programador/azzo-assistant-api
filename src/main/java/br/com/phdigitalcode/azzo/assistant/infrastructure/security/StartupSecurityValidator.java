package br.com.phdigitalcode.azzo.assistant.infrastructure.security;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Valida na inicializacao que segredos criticos nao usam o valor default
 * inseguro em producao. Em prod, aborta o startup; em dev, apenas avisa.
 */
@ApplicationScoped
public class StartupSecurityValidator {

  private static final Logger LOG = Logger.getLogger(StartupSecurityValidator.class);
  private static final String DEFAULT_INTERNAL_KEY = "changeme-dev";

  @ConfigProperty(name = "app.internal.api-key", defaultValue = "changeme-dev")
  String internalApiKey;

  @ConfigProperty(name = "app.assistant.debug", defaultValue = "false")
  boolean debugEnabled;

  @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
  String profile;

  void onStart(@Observes StartupEvent event) {
    boolean isProd = "prod".equals(profile);

    if (DEFAULT_INTERNAL_KEY.equals(internalApiKey)) {
      if (isProd) {
        throw new IllegalStateException(
            "SEGURANCA: app.internal.api-key esta com o valor default inseguro. " +
            "Defina INTERNAL_API_KEY como variavel de ambiente antes de iniciar em producao.");
      }
      LOG.warn("SEGURANCA: app.internal.api-key esta com o valor default. Nao use em producao.");
    }

    if (isProd && debugEnabled) {
      throw new IllegalStateException(
          "SEGURANCA/LGPD: app.assistant.debug=true em producao expoe mensagens e PII em log. " +
          "Defina APP_ASSISTANT_DEBUG=false em producao.");
    }
  }
}
