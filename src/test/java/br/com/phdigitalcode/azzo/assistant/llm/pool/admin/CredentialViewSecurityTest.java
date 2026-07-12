package br.com.phdigitalcode.azzo.assistant.llm.pool.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.phdigitalcode.azzo.assistant.llm.pool.admin.LlmPoolAdminDtos.CredentialView;
import br.com.phdigitalcode.azzo.assistant.llm.pool.domain.entity.LlmCredential;
import org.junit.jupiter.api.Test;

/** Garante que a view de credencial nunca carrega a chave (real ou cifrada). */
class CredentialViewSecurityTest {

  private static final String CIPHERTEXT = "BLOB_CIFRADO_naoDeveVazar_abc123";
  private static final String PLAINTEXT = "gsk_chaveSecretaReal1234567890";

  @Test
  void viewNaoExpoeChaveNemCifrada() throws Exception {
    LlmCredential c = new LlmCredential();
    c.id = UUID.randomUUID();
    c.providerId = UUID.randomUUID();
    c.nomeIdentificacao = "chave-1";
    c.apiKeyCriptografada = CIPHERTEXT;
    c.apiKeyMascara = "gsk_••••7890";

    CredentialView v = CredentialView.de(c);
    assertEquals("gsk_••••7890", v.chaveMascarada);

    String json = new ObjectMapper().writeValueAsString(v);
    assertFalse(json.contains(CIPHERTEXT), "a resposta nunca pode conter a chave cifrada");
    assertFalse(json.contains(PLAINTEXT), "a resposta nunca pode conter a chave em claro");
    assertTrue(json.contains("chaveMascarada"), "deve expor apenas a mascara");
  }
}
