package br.com.phdigitalcode.azzo.assistant.llm.pool.routing;

import java.util.Optional;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.RoutingStrategy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Override em memória da estratégia de roteamento, ajustável pela API admin. Quando
 * não definido, o roteador usa o valor configurado ({@code assistant.llm.pool.default-strategy}).
 *
 * <p>Limitação conhecida: o override é por instância. Em cluster, defina a estratégia
 * padrão por variável de ambiente para consistência entre instâncias, ou promova este
 * holder a uma tabela de configuração numa fase futura.
 */
@ApplicationScoped
public class RoutingStrategyHolder {

  private volatile RoutingStrategy override;

  public Optional<RoutingStrategy> override() {
    return Optional.ofNullable(override);
  }

  public void definir(RoutingStrategy estrategia) {
    this.override = estrategia;
  }

  public void limpar() {
    this.override = null;
  }
}
