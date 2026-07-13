package br.com.phdigitalcode.azzo.assistant.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Serviço centralizado de criptografia de credenciais (API keys de provedores de LLM).
 *
 * <p>Reutiliza EXATAMENTE o mesmo padrão seguro usado pelo agenda-pro para o token do
 * WhatsApp (ver {@code modules.security.infrastructure.EncryptionService}):
 * <b>AES/GCM/NoPadding</b>, IV aleatório de 12 bytes prefixado ao ciphertext, tag de
 * autenticação de 128 bits, tudo codificado em Base64. A chave vem de configuração e
 * aceita 16, 24 ou 32 bytes (base64 ou texto puro).
 *
 * <p>Como o pool de provedores roda no serviço azzo-assistant-api (separado do agenda-pro),
 * o algoritmo é replicado aqui em vez de importado — os dois serviços não compartilham
 * biblioteca. A chave é um segredo próprio do assistant ({@code assistant.security.encryption-key}).
 *
 * <p>Regras de segurança: nunca logar a chave nem o valor em claro; a descriptografia
 * ocorre apenas no momento da chamada ao provedor e pelo menor tempo possível.
 */
@ApplicationScoped
public class CredentialEncryptionService {

  private static final int IV_LENGTH = 12;
  private static final int TAG_LENGTH = 128;

  private final SecureRandom secureRandom = new SecureRandom();
  private final SecretKeySpec keySpec;

  public CredentialEncryptionService(
      // Mesma chave do agenda-pro: la e app.security.encryption-key=${ENCRYPTION_KEY}.
      // Aqui a propriedade 'encryption.key' e mapeada pelo Quarkus para a env ENCRYPTION_KEY,
      // com default vazio para nao exigir a chave no fluxo legado (o agenda exige; aqui nao).
      @ConfigProperty(name = "encryption.key", defaultValue = "") String encryptionKey) {
    // Chave ausente é tolerada no boot (retrocompatibilidade): a falha só ocorre ao
    // efetivamente cifrar/decifrar sem chave, não ao subir a aplicação no fluxo legado.
    this.keySpec = (encryptionKey == null || encryptionKey.isBlank())
        ? null
        : new SecretKeySpec(parseKey(encryptionKey), "AES");
  }

  private SecretKeySpec requireKey() {
    if (keySpec == null) {
      throw new IllegalStateException(
          "Chave de criptografia nao configurada (env ENCRYPTION_KEY, a mesma do agenda-pro)");
    }
    return keySpec;
  }

  /** Indica se a criptografia está pronta para uso (chave configurada). */
  public boolean isConfigured() {
    return keySpec != null;
  }

  /** Criptografa um valor. Valor nulo/vazio retorna string vazia (não persiste segredo inexistente). */
  public String encrypt(String value) {
    if (value == null || value.isBlank()) return "";
    SecretKeySpec key = requireKey();
    try {
      byte[] iv = new byte[IV_LENGTH];
      secureRandom.nextBytes(iv);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

      byte[] payload = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, payload, 0, iv.length);
      System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(payload);
    } catch (Exception e) {
      // Nunca inclui o valor em claro na exceção.
      throw new IllegalStateException("Falha ao criptografar credencial", e);
    }
  }

  /** Descriptografa um valor previamente cifrado por {@link #encrypt(String)}. */
  public String decrypt(String encryptedValue) {
    if (encryptedValue == null || encryptedValue.isBlank()) return "";
    SecretKeySpec key = requireKey();
    try {
      byte[] payload = Base64.getDecoder().decode(encryptedValue);
      if (payload.length <= IV_LENGTH) {
        throw new IllegalArgumentException("Valor criptografado invalido");
      }
      byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
      byte[] cipherBytes = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
      byte[] decrypted = cipher.doFinal(cipherBytes);
      return new String(decrypted, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Falha ao descriptografar credencial", e);
    }
  }

  /**
   * Máscara para exibição segura (ex.: {@code gsk_••••••••4F8A}). Nunca revela o miolo
   * do segredo: mostra apenas um pequeno prefixo e sufixo do valor em claro.
   */
  public String mask(String plainValue) {
    if (plainValue == null || plainValue.isBlank()) return "";
    String v = plainValue.trim();
    int len = v.length();
    if (len <= 8) {
      return "•".repeat(Math.max(4, len));
    }
    String prefix = v.substring(0, Math.min(4, len));
    String suffix = v.substring(len - 4);
    return prefix + "••••••••" + suffix;
  }

  private byte[] parseKey(String configuredKey) {
    if (configuredKey == null || configuredKey.isBlank()) {
      throw new IllegalStateException("Chave de criptografia nao configurada (assistant.security.encryption-key)");
    }
    byte[] decoded = tryDecodeBase64(configuredKey);
    if (isValidAesKey(decoded)) return decoded;

    byte[] raw = configuredKey.getBytes(StandardCharsets.UTF_8);
    if (isValidAesKey(raw)) return raw;

    throw new IllegalStateException("Chave de criptografia invalida. Use 16, 24 ou 32 bytes.");
  }

  private byte[] tryDecodeBase64(String value) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      return new byte[0];
    }
  }

  private boolean isValidAesKey(byte[] bytes) {
    return bytes.length == 16 || bytes.length == 24 || bytes.length == 32;
  }
}
