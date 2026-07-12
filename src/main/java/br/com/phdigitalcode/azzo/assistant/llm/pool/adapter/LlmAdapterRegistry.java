package br.com.phdigitalcode.azzo.assistant.llm.pool.adapter;

import java.util.EnumMap;
import java.util.Map;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.enums.LlmProviderType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Registro de adaptadores por {@link LlmProviderType}, resolvido via CDI. Substitui
 * condicionais {@code if (provider.equals("GROQ"))} espalhados: para adicionar um
 * provedor com API própria basta criar um bean {@link LlmProviderAdapter}.
 *
 * <p>Tipos compatíveis com OpenAI que não tenham adaptador dedicado caem
 * automaticamente no {@link OpenAiCompatibleAdapter}.
 */
@ApplicationScoped
public class LlmAdapterRegistry {

  private static final Logger LOG = Logger.getLogger(LlmAdapterRegistry.class);

  @Inject Instance<LlmProviderAdapter> adapters;
  @Inject OpenAiCompatibleAdapter openAiCompatibleAdapter;

  private final Map<LlmProviderType, LlmProviderAdapter> porTipo = new EnumMap<>(LlmProviderType.class);

  @PostConstruct
  void init() {
    for (LlmProviderAdapter adapter : adapters) {
      LlmProviderType tipo = adapter.getProviderType();
      LlmProviderAdapter anterior = porTipo.put(tipo, adapter);
      if (anterior != null && anterior != adapter) {
        LOG.warnf("[AdapterRegistry] Mais de um adaptador para %s — usando %s",
            tipo, adapter.getClass().getSimpleName());
      }
    }
    LOG.infof("[AdapterRegistry] %d adaptador(es) registrado(s): %s", porTipo.size(), porTipo.keySet());
  }

  /**
   * Resolve o adaptador do tipo. Se não houver dedicado e o tipo for compatível com
   * OpenAI, usa o adaptador base. Lança {@link IllegalStateException} para tipos sem
   * adaptador — a arquitetura fica preparada para incluí-los depois.
   */
  public LlmProviderAdapter resolver(LlmProviderType tipo) {
    LlmProviderAdapter adapter = porTipo.get(tipo);
    if (adapter != null) return adapter;
    if (tipo != null && tipo.isOpenAiCompatible()) return openAiCompatibleAdapter;
    throw new IllegalStateException("Nenhum adaptador disponível para o provedor tipo=" + tipo);
  }

  public boolean suporta(LlmProviderType tipo) {
    return porTipo.containsKey(tipo) || (tipo != null && tipo.isOpenAiCompatible());
  }
}
