package br.com.phdigitalcode.azzo.assistant.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DateTimeRegexExtractor")
class DateTimeRegexExtractorTest {

    // ─── extractDate ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("'hoje' retorna a data atual")
    void extractDate_hoje() {
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate("hoje");
        assertTrue(result.isPresent());
        assertEquals(LocalDate.now(), result.get());
    }

    @Test
    @DisplayName("'amanha' retorna tomorrow")
    void extractDate_amanha() {
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate("amanhã");
        assertTrue(result.isPresent());
        assertEquals(LocalDate.now().plusDays(1), result.get());
    }

    @Test
    @DisplayName("'amanha' sem acento retorna tomorrow")
    void extractDate_amanhaSemAcento() {
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate("amanha");
        assertTrue(result.isPresent());
        assertEquals(LocalDate.now().plusDays(1), result.get());
    }

    @Test
    @DisplayName("data no formato dd/MM/yyyy é extraída corretamente")
    void extractDate_formatoDMY_completo() {
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate("25/03/2026");
        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(2026, 3, 25), result.get());
    }

    @Test
    @DisplayName("data no formato dd/MM sem ano usa ano atual")
    void extractDate_formatoDM_semAno() {
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate("15/07");
        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(LocalDate.now().getYear(), 7, 15), result.get());
    }

    @Test
    @DisplayName("data no formato ISO yyyy-MM-dd é extraída")
    void extractDate_formatoISO() {
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate("2026-06-10");
        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(2026, 6, 10), result.get());
    }

    @Test
    @DisplayName("data com hífen dd-MM-yyyy é extraída")
    void extractDate_formatoComHifen() {
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate("20-04-2026");
        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(2026, 4, 20), result.get());
    }

    @Test
    @DisplayName("ano de 2 dígitos é tratado como 20xx")
    void extractDate_anoAbreviado() {
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate("10/08/26");
        assertTrue(result.isPresent());
        assertEquals(LocalDate.of(2026, 8, 10), result.get());
    }

    @Test
    @DisplayName("texto sem data retorna Optional.empty()")
    void extractDate_semData_retornaEmpty() {
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate("quero cortar o cabelo");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("texto null retorna Optional.empty()")
    void extractDate_null_retornaEmpty() {
        // TextNormalizer.normalize(null) retorna "" então não há crash
        Optional<LocalDate> result = DateTimeRegexExtractor.extractDate(null);
        assertTrue(result.isEmpty());
    }

    // ─── extractTime ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("'10h' extrai horário 10:00")
    void extractTime_10h() {
        Optional<String> result = DateTimeRegexExtractor.extractTime("10h");
        assertTrue(result.isPresent());
        assertEquals("10:00", result.get());
    }

    @Test
    @DisplayName("'10:30' extrai horário 10:30")
    void extractTime_10h30() {
        Optional<String> result = DateTimeRegexExtractor.extractTime("10:30");
        assertTrue(result.isPresent());
        assertEquals("10:30", result.get());
    }

    @Test
    @DisplayName("'14h30' extrai horário 14:30")
    void extractTime_14h30() {
        Optional<String> result = DateTimeRegexExtractor.extractTime("14h30");
        assertTrue(result.isPresent());
        assertEquals("14:30", result.get());
    }

    @Test
    @DisplayName("'9h' extrai horário 09:00")
    void extractTime_9h() {
        Optional<String> result = DateTimeRegexExtractor.extractTime("9h");
        assertTrue(result.isPresent());
        assertEquals("09:00", result.get());
    }

    @Test
    @DisplayName("'8:00' extrai horário 08:00")
    void extractTime_8h00() {
        Optional<String> result = DateTimeRegexExtractor.extractTime("8:00");
        assertTrue(result.isPresent());
        assertEquals("08:00", result.get());
    }

    @Test
    @DisplayName("texto sem horário retorna Optional.empty()")
    void extractTime_semHorario_retornaEmpty() {
        Optional<String> result = DateTimeRegexExtractor.extractTime("quero agendar");
        assertTrue(result.isEmpty());
    }

    // ─── isAffirmative ────────────────────────────────────────────────────────

    @ParameterizedTest(name = "'{0}' deve ser afirmativo")
    @ValueSource(strings = {"sim", "s", "ok", "confirmar"})
    @DisplayName("respostas afirmativas são reconhecidas")
    void isAffirmative_true(String input) {
        assertTrue(DateTimeRegexExtractor.isAffirmative(input));
    }

    @ParameterizedTest(name = "'{0}' NÃO deve ser afirmativo")
    @ValueSource(strings = {"nao", "n", "cancelar", "talvez", "ok nao"})
    @DisplayName("respostas não-afirmativas retornam false")
    void isAffirmative_false(String input) {
        assertFalse(DateTimeRegexExtractor.isAffirmative(input));
    }

    // ─── isNegative ───────────────────────────────────────────────────────────

    @ParameterizedTest(name = "'{0}' deve ser negativo")
    @ValueSource(strings = {"nao", "n", "cancelar"})
    @DisplayName("respostas negativas são reconhecidas")
    void isNegative_true(String input) {
        assertTrue(DateTimeRegexExtractor.isNegative(input));
    }

    @ParameterizedTest(name = "'{0}' NÃO deve ser negativo")
    @ValueSource(strings = {"sim", "s", "ok", "talvez"})
    @DisplayName("respostas não-negativas retornam false")
    void isNegative_false(String input) {
        assertFalse(DateTimeRegexExtractor.isNegative(input));
    }
}
