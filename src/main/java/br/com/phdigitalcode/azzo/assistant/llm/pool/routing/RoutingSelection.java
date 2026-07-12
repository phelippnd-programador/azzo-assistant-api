package br.com.phdigitalcode.azzo.assistant.llm.pool.routing;

import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmModel;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmProvider;

/**
 * Uma opção resolvida (provedor + credencial + modelo) pronta para execução.
 * O executor decripta a chave apenas no momento da chamada.
 */
public record RoutingSelection(LlmProvider provider, LlmCredential credential, LlmModel model) {}
