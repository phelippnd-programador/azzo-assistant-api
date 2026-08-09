package br.com.phdigitalcode.azzo.assistant.dialogue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TimePeriod")
class TimePeriodTest {

    // ─── fromText ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("null deve retornar Optional.empty()")
    void fromText_null_retornaEmpty() {
        assertEquals(Optional.empty(), TimePeriod.fromText(null));
    }

    @Test
    @DisplayName("texto não reconhecido retorna Optional.empty()")
    void fromText_desconhecido_retornaEmpty() {
        assertEquals(Optional.empty(), TimePeriod.fromText("xyz"));
        assertEquals(Optional.empty(), TimePeriod.fromText(""));
        assertEquals(Optional.empty(), TimePeriod.fromText("   "));
    }

    @ParameterizedTest(name = "'{0}' deve mapear para MORNING")
    @ValueSource(strings = {"manha", "manhã", "de manha", "pela manha", "MANHA"})
    @DisplayName("variações de manhã mapeiam para MORNING")
    void fromText_manha_retornaMorning(String input) {
        Optional<TimePeriod> result = TimePeriod.fromText(input);
        assertTrue(result.isPresent());
        assertEquals(TimePeriod.MORNING, result.get());
    }

    @ParameterizedTest(name = "'{0}' deve mapear para AFTERNOON")
    @ValueSource(strings = {"tarde", "a tarde", "pela tarde", "TARDE"})
    @DisplayName("variações de tarde mapeiam para AFTERNOON")
    void fromText_tarde_retornaAfternoon(String input) {
        Optional<TimePeriod> result = TimePeriod.fromText(input);
        assertTrue(result.isPresent());
        assertEquals(TimePeriod.AFTERNOON, result.get());
    }

    @ParameterizedTest(name = "'{0}' deve mapear para NIGHT")
    @ValueSource(strings = {"noite", "a noite", "pela noite", "NOITE"})
    @DisplayName("variações de noite mapeiam para NIGHT")
    void fromText_noite_retornaNight(String input) {
        Optional<TimePeriod> result = TimePeriod.fromText(input);
        assertTrue(result.isPresent());
        assertEquals(TimePeriod.NIGHT, result.get());
    }

    // ─── label() ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("label() retorna 'manha' para MORNING")
    void label_morning() {
        assertEquals("manha", TimePeriod.MORNING.label());
    }

    @Test
    @DisplayName("label() retorna 'tarde' para AFTERNOON")
    void label_afternoon() {
        assertEquals("tarde", TimePeriod.AFTERNOON.label());
    }

    @Test
    @DisplayName("label() retorna 'noite' para NIGHT")
    void label_night() {
        assertEquals("noite", TimePeriod.NIGHT.label());
    }

    // ─── fromHour ─────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "hora {0} deve mapear para MORNING")
    @ValueSource(ints = {0, 6, 8, 11})
    @DisplayName("horas antes das 12h mapeiam para MORNING")
    void fromHour_manha(int hour) {
        assertEquals(TimePeriod.MORNING, TimePeriod.fromHour(hour));
    }

    @ParameterizedTest(name = "hora {0} deve mapear para AFTERNOON")
    @ValueSource(ints = {12, 14, 17})
    @DisplayName("horas entre 12h e 17h59 mapeiam para AFTERNOON")
    void fromHour_tarde(int hour) {
        assertEquals(TimePeriod.AFTERNOON, TimePeriod.fromHour(hour));
    }

    @ParameterizedTest(name = "hora {0} deve mapear para NIGHT")
    @ValueSource(ints = {18, 20, 23})
    @DisplayName("horas a partir das 18h mapeiam para NIGHT")
    void fromHour_noite(int hour) {
        assertEquals(TimePeriod.NIGHT, TimePeriod.fromHour(hour));
    }

    // ─── enum values ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("enum possui exatamente 3 valores")
    void enum_possuiTresValores() {
        assertEquals(3, TimePeriod.values().length);
    }
}
