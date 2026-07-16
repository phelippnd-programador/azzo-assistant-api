package br.com.phdigitalcode.azzo.assistant.application.service;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Serializa o processamento de mensagens do mesmo tenant+telefone dentro desta
 * instância do serviço.
 *
 * <p>Problema que resolve: em {@code AssistantConversationService.process()} o LLM é
 * chamado FORA de transação (pode levar 30-120s) e o estado da conversa é carregado
 * numa transação curta e salvo em outra, bem depois. Sem serialização, duas mensagens
 * quase simultâneas do mesmo cliente podem: (a) ambas não encontrarem conversa ativa e
 * criarem duas linhas de estado duplicadas, ou (b) a que salvar por último sobrescrever
 * integralmente o {@code stateJson} da outra, perdendo slots preenchidos pela mensagem
 * mais rápida ("lost update").
 *
 * <p>Implementação: locks em memória distribuídos em um número fixo de "stripes"
 * (semelhante a {@code com.google.common.util.concurrent.Striped}, sem depender do
 * Guava, que não é dependência deste projeto). O número de locks é limitado a
 * {@link #STRIPE_COUNT}, então o consumo de memória não cresce com o número de
 * tenants/telefones distintos — o trade-off é que duas chaves diferentes podem, em
 * casos raros, cair na mesma stripe e serializar sem necessidade (falso positivo),
 * nunca o contrário.
 *
 * <p><b>Limitação conhecida — só protege uma única instância/réplica.</b> Este lock
 * vive na memória da JVM. Se o serviço rodar com mais de uma réplica (múltiplos pods
 * no k8s, múltiplos containers atrás de um load balancer, etc.), duas mensagens do
 * mesmo tenant+telefone podem ser atendidas por réplicas diferentes e o race condition
 * volta a existir, pois cada réplica tem seu próprio mapa de locks. Nesse cenário seria
 * necessário lock distribuído (ex.: advisory lock do Postgres, Redis, etc.) — fora do
 * escopo deste fix. Verificado no repositório: não há configuração de múltiplas réplicas
 * para o azzo-assistant-api (o workflow de CI só builda e publica a imagem; não há
 * docker-compose nem manifesto k8s com `replicas`/`scale` para este serviço), então o
 * lock em memória é suficiente no deployment atual.
 */
@ApplicationScoped
public class ConversationLockManager {

  private static final int STRIPE_COUNT = 256;

  private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

  public ConversationLockManager() {
    for (int i = 0; i < STRIPE_COUNT; i++) {
      stripes[i] = new ReentrantLock();
    }
  }

  /**
   * Executa {@code action} com exclusividade para a chave informada (tipicamente
   * {@code tenantId + ":" + userIdentifier}). Bloqueia a thread chamadora até o lock
   * ficar livre — aceitável aqui porque cada mensagem de WhatsApp já é processada de
   * forma serial por cliente; o objetivo é justamente impedir processamento concorrente
   * da mesma conversa.
   */
  public <T> T withLock(String key, Supplier<T> action) {
    ReentrantLock lock = stripeFor(key);
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  private ReentrantLock stripeFor(String key) {
    int index = Math.floorMod(key.hashCode(), STRIPE_COUNT);
    return stripes[index];
  }
}
