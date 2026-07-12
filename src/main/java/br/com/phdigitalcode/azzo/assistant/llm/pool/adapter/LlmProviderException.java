package br.com.phdigitalcode.azzo.assistant.llm.pool.adapter;

/**
 * Falha ao chamar um provedor. Carrega o código HTTP e o Retry-After (quando houver)
 * para o roteador tratar 429/limites e bloquear a credencial adequadamente.
 * A mensagem nunca contém a API key.
 */
public class LlmProviderException extends RuntimeException {

  private final int httpStatus;
  private final Long retryAfterMs;

  public LlmProviderException(int httpStatus, Long retryAfterMs, String message) {
    super(message);
    this.httpStatus = httpStatus;
    this.retryAfterMs = retryAfterMs;
  }

  public LlmProviderException(int httpStatus, Long retryAfterMs, String message, Throwable cause) {
    super(message, cause);
    this.httpStatus = httpStatus;
    this.retryAfterMs = retryAfterMs;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public Long getRetryAfterMs() {
    return retryAfterMs;
  }

  public boolean isRateLimited() {
    return httpStatus == 429;
  }

  public boolean isAuthError() {
    return httpStatus == 401 || httpStatus == 403;
  }

  public boolean isServerError() {
    return httpStatus >= 500;
  }
}
