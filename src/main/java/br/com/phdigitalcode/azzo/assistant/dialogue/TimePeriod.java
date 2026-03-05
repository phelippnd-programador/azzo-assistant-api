package br.com.phdigitalcode.azzo.assistant.dialogue;

import java.util.Locale;
import java.util.Optional;

public enum TimePeriod {
  MORNING("manha"),
  AFTERNOON("tarde"),
  NIGHT("noite");

  private final String label;

  TimePeriod(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static Optional<TimePeriod> fromText(String rawText) {
    if (rawText == null) return Optional.empty();
    String value = rawText.trim().toLowerCase(Locale.ROOT);
    if (value.contains("manha") || value.contains("manhã")) return Optional.of(MORNING);
    if (value.contains("tarde")) return Optional.of(AFTERNOON);
    if (value.contains("noite")) return Optional.of(NIGHT);
    return Optional.empty();
  }
}
