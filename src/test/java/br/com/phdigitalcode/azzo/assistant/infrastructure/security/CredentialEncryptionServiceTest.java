package br.com.phdigitalcode.azzo.assistant.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Testes puros da criptografia de credenciais (sem CDI/DB). */
class CredentialEncryptionServiceTest {

  private static final String KEY = "0123456789abcdef0123456789abcdef"; // 32 bytes

  private CredentialEncryptionService service() {
    return new CredentialEncryptionService(KEY);
  }

  @Test
  void roundTripPreservaValor() {
    CredentialEncryptionService s = service();
    String original = "gsk_abcdef1234567890XYZ";
    String cifrado = s.encrypt(original);

    assertNotEquals(original, cifrado, "o valor cifrado nao pode ser igual ao original");
    assertEquals(original, s.decrypt(cifrado));
  }

  @Test
  void mesmoValorGeraCiphertextsDiferentes() {
    CredentialEncryptionService s = service();
    // IV aleatorio: duas cifragens do mesmo valor devem diferir.
    assertNotEquals(s.encrypt("segredo"), s.encrypt("segredo"));
  }

  @Test
  void valorVazioNaoGeraSegredo() {
    CredentialEncryptionService s = service();
    assertEquals("", s.encrypt(null));
    assertEquals("", s.encrypt(""));
    assertEquals("", s.decrypt(""));
  }

  @Test
  void mascaraNaoRevelaMiolo() {
    CredentialEncryptionService s = service();
    String chave = "gsk_abcdefghijkl4F8A";
    String mascara = s.mask(chave);

    assertTrue(mascara.startsWith("gsk_"), "deve manter um pequeno prefixo");
    assertTrue(mascara.endsWith("4F8A"), "deve manter um pequeno sufixo");
    assertFalse(mascara.contains("abcdefghijkl"), "nunca pode revelar o miolo do segredo");
  }

  @Test
  void semChaveConfiguradaFalhaAoUsarNaoAoConstruir() {
    // Boot tolerante: construir sem chave nao lanca (fluxo legado).
    CredentialEncryptionService semChave = new CredentialEncryptionService("");
    assertFalse(semChave.isConfigured());
    // Mas usar a criptografia sem chave lanca.
    assertThrows(IllegalStateException.class, () -> semChave.encrypt("x"));
  }

  @Test
  void chaveInvalidaNaoQuebraOBoot() {
    // Chave presente porém com tamanho invalido (nao 16/24/32 bytes): o construtor
    // NAO pode lancar (nao pode derrubar o boot) — apenas desabilita a criptografia.
    CredentialEncryptionService chaveRuim = new CredentialEncryptionService("chave-curta");
    assertFalse(chaveRuim.isConfigured());
    assertThrows(IllegalStateException.class, () -> chaveRuim.encrypt("x"));
  }
}
