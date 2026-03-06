package br.com.phdigitalcode.azzo.assistant.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TextNormalizer")
class TextNormalizerTest {

    @Test
    @DisplayName("null deve retornar string vazia")
    void normalize_null_retornaVazio() {
        assertEquals("", TextNormalizer.normalize(null));
    }

    @Test
    @DisplayName("string vazia permanece vazia")
    void normalize_vazioPermaneceVazio() {
        assertEquals("", TextNormalizer.normalize(""));
    }

    @Test
    @DisplayName("texto simples converte para minúsculas")
    void normalize_converteMinusculas() {
        assertEquals("ola mundo", TextNormalizer.normalize("Ola Mundo"));
    }

    @Test
    @DisplayName("acentos são removidos")
    void normalize_removeAcentos() {
        assertEquals("ola", TextNormalizer.normalize("Olá"));
        assertEquals("cafe", TextNormalizer.normalize("Café"));
        assertEquals("cao", TextNormalizer.normalize("Cão"));
        assertEquals("acucar", TextNormalizer.normalize("Açúcar"));
    }

    @Test
    @DisplayName("espaços nas bordas são removidos (trim)")
    void normalize_trim() {
        assertEquals("helio", TextNormalizer.normalize("  Hélio  "));
    }

    @Test
    @DisplayName("texto com cedilha é normalizado")
    void normalize_cedilha() {
        assertEquals("servico", TextNormalizer.normalize("Serviço"));
    }

    @Test
    @DisplayName("texto com til é normalizado")
    void normalize_til() {
        assertEquals("manha", TextNormalizer.normalize("Manhã"));
        assertEquals("nao", TextNormalizer.normalize("Não"));
    }

    @Test
    @DisplayName("texto misto de acentos e maiúsculas")
    void normalize_mistoCompleto() {
        assertEquals("agendamento", TextNormalizer.normalize("AGENDAMENTO"));
        assertEquals("profissional", TextNormalizer.normalize("Profissional"));
    }

    @Test
    @DisplayName("dígitos e caracteres especiais permanecem")
    void normalize_digitosPermanecem() {
        assertEquals("25/03/2026", TextNormalizer.normalize("25/03/2026"));
        assertEquals("10:30", TextNormalizer.normalize("10:30"));
    }
}
