package br.com.phdigitalcode.azzo.assistant.util;

import java.text.Normalizer;
import java.util.Locale;

public final class TextNormalizer {

  private TextNormalizer() {}

  public static String normalize(String input) {
    if (input == null) return "";
    String cleaned = Normalizer.normalize(input, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    return cleaned.trim().toLowerCase(Locale.ROOT);
  }
}
