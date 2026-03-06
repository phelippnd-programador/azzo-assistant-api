package br.com.phdigitalcode.azzo.assistant.util;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DateTimeRegexExtractor {

  private static final Pattern DATE_DMY = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b");
  private static final Pattern DATE_YMD = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
  private static final Pattern TIME = Pattern.compile("\\b([01]?\\d|2[0-3])(?:[:h]([0-5]\\d))?\\b");

  private DateTimeRegexExtractor() {}

  public static Optional<LocalDate> extractDate(String text) {
    String normalized = TextNormalizer.normalize(text);
    LocalDate today = LocalDate.now();
    if (normalized.contains("hoje")) return Optional.of(today);
    if (normalized.contains("amanha")) return Optional.of(today.plusDays(1));

    Matcher ymdMatcher = DATE_YMD.matcher(normalized);
    if (ymdMatcher.find()) {
      try {
        return Optional.of(LocalDate.parse(ymdMatcher.group(), DateTimeFormatter.ISO_LOCAL_DATE));
      } catch (DateTimeParseException ignored) {
      }
    }

    Matcher dmyMatcher = DATE_DMY.matcher(normalized);
    if (dmyMatcher.find()) {
      int day = Integer.parseInt(dmyMatcher.group(1));
      int month = Integer.parseInt(dmyMatcher.group(2));
      String yearGroup = dmyMatcher.group(3);
      int year = yearGroup == null ? today.getYear() : normalizeYear(yearGroup);
      try {
        return Optional.of(LocalDate.of(year, month, day));
      } catch (RuntimeException ignored) {
      }
    }

    return Optional.empty();
  }

  public static Optional<String> extractTime(String text) {
    Matcher matcher = TIME.matcher(TextNormalizer.normalize(text));
    if (!matcher.find()) return Optional.empty();

    int hour = Integer.parseInt(matcher.group(1));
    int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
    try {
      return Optional.of(LocalTime.of(hour, minute).toString());
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  public static boolean isAffirmative(String text) {
    String normalized = TextNormalizer.normalize(text);
    return normalized.equals("sim") || normalized.equals("s") || normalized.equals("ok") || normalized.equals("confirmar");
  }

  public static boolean isNegative(String text) {
    String normalized = TextNormalizer.normalize(text);
    return normalized.equals("nao") || normalized.equals("n") || normalized.equals("cancelar");
  }

  private static int normalizeYear(String year) {
    if (year.length() == 2) {
      return Integer.parseInt("20" + year);
    }
    return Integer.parseInt(year);
  }
}
