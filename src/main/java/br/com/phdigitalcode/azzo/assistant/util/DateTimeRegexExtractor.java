package br.com.phdigitalcode.azzo.assistant.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DateTimeRegexExtractor {

  private static final Pattern DATE_DMY = Pattern.compile("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b");
  private static final Pattern DATE_YMD = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
  // O "h" sozinho (sem minutos, ex.: "10h", "9h") também é um match válido —
  // sem essa alternativa literal, o \b final falhava (dígito e "h" são ambos
  // \w, então não há fronteira entre eles quando não sobra nada depois do "h").
  private static final Pattern TIME = Pattern.compile("\\b([01]?\\d|2[0-3])(?:[:h]([0-5]\\d)|h)?\\b");
  // Exige HH:MM (ou HHhMM) — sem isso não aceita bare ordinal como "1" ou dígito de data como "06"
  private static final Pattern TIME_STRICT = Pattern.compile("\\b([01]?\\d|2[0-3])[:h]([0-5]\\d)\\b");
  // Hora "solta" seguida de sufixo horário explícito: "17h", "17hs", "17 horas", "17h00"
  private static final Pattern TIME_H_SUFFIX =
      Pattern.compile("\\b([01]?\\d|2[0-3])\\s*h(?:oras?|s)?(?:\\s*([0-5]\\d))?\\b");
  // Preposição + número solto: "as 17", "às 17" (normalizado remove o acento)
  private static final Pattern TIME_PREPOSITION = Pattern.compile("\\bas\\s+([01]?\\d|2[0-3])\\b");
  // Dígito + período explícito: "5 da tarde"
  private static final Pattern TIME_DIGIT_PERIOD =
      Pattern.compile("\\b([01]?\\d|2[0-3])\\s+da\\s+(manha|tarde|noite)\\b");

  private static final Map<String, Integer> NUMBER_WORDS = new LinkedHashMap<>();
  static {
    NUMBER_WORDS.put("uma", 1);
    NUMBER_WORDS.put("um", 1);
    NUMBER_WORDS.put("duas", 2);
    NUMBER_WORDS.put("dois", 2);
    NUMBER_WORDS.put("tres", 3);
    NUMBER_WORDS.put("quatro", 4);
    NUMBER_WORDS.put("cinco", 5);
    NUMBER_WORDS.put("seis", 6);
    NUMBER_WORDS.put("sete", 7);
    NUMBER_WORDS.put("oito", 8);
    NUMBER_WORDS.put("nove", 9);
    NUMBER_WORDS.put("dez", 10);
    NUMBER_WORDS.put("onze", 11);
    NUMBER_WORDS.put("doze", 12);
  }

  // Chaves ja normalizadas (sem acento, minusculo — ver TextNormalizer).
  private static final Map<String, DayOfWeek> WEEKDAYS = new LinkedHashMap<>();
  static {
    WEEKDAYS.put("segunda", DayOfWeek.MONDAY);
    WEEKDAYS.put("terca", DayOfWeek.TUESDAY);
    WEEKDAYS.put("quarta", DayOfWeek.WEDNESDAY);
    WEEKDAYS.put("quinta", DayOfWeek.THURSDAY);
    WEEKDAYS.put("sexta", DayOfWeek.FRIDAY);
    WEEKDAYS.put("sabado", DayOfWeek.SATURDAY);
    WEEKDAYS.put("domingo", DayOfWeek.SUNDAY);
  }

  private DateTimeRegexExtractor() {}

  public static Optional<LocalDate> extractDate(String text) {
    String normalized = TextNormalizer.normalize(text);
    LocalDate today = LocalDate.now();
    // "depois de amanha" antes de "amanha" — senao o contains("amanha") o captura como amanha.
    if (normalized.contains("depois de amanha")) return Optional.of(today.plusDays(2));
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

    Optional<LocalDate> weekday = extractWeekday(normalized, today);
    if (weekday.isPresent()) return weekday;

    return Optional.empty();
  }

  /**
   * Resolve nome de dia da semana ("segunda", "segunda-feira", "terca"...) para a
   * PROXIMA ocorrencia futura — mesma semantica de
   * AssistantConversationService#resolveDayOfWeek (nunca hoje: se hoje e segunda e o
   * cliente diz "segunda", retorna a proxima segunda). Sem isso, datas informadas por
   * dia da semana nao eram capturadas deterministicamente (dependiam so do LLM).
   */
  private static Optional<LocalDate> extractWeekday(String normalized, LocalDate today) {
    for (Map.Entry<String, DayOfWeek> entry : WEEKDAYS.entrySet()) {
      Matcher matcher = Pattern.compile("\\b" + entry.getKey() + "\\b").matcher(normalized);
      if (matcher.find()) {
        // "segunda" tambem e ordinal ("segunda opcao", "segunda vez"): nesses casos nao
        // e dia da semana. Os demais dias nao tem essa ambiguidade.
        if ("segunda".equals(entry.getKey())) {
          String after = normalized.substring(matcher.end()).stripLeading();
          if (after.startsWith("opcao") || after.startsWith("opcoes")
              || after.startsWith("vez") || after.startsWith("melhor") || after.startsWith("pior")) {
            continue;
          }
        }
        return Optional.of(today.plusDays(1).with(TemporalAdjusters.nextOrSame(entry.getValue())));
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

  /**
   * Extrai horário apenas quando o formato HH:MM ou HHhMM está explícito.
   * Use para contextos onde o texto pode conter números que não são horários
   * (datas, ordinais de seleção, etc.) para evitar falsos positivos.
   */
  public static Optional<String> extractTimeStrict(String text) {
    Matcher matcher = TIME_STRICT.matcher(TextNormalizer.normalize(text));
    if (!matcher.find()) return Optional.empty();

    int hour = Integer.parseInt(matcher.group(1));
    int minute = Integer.parseInt(matcher.group(2));
    try {
      return Optional.of(LocalTime.of(hour, minute).toString());
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  /**
   * Extrai horário de expressões coloquiais que extractTimeStrict não cobre:
   * "17h", "17hs", "17 horas", "às 17", "as 17", "5 da tarde", "cinco da tarde",
   * "cinco horas da tarde". Agnóstico a modelo/provedor — resolução 100% determinística.
   * Sempre prioriza o padrão mais explícito (HH:MM) antes de tentar formas soltas.
   */
  public static Optional<String> extractTimeLoose(String text) {
    Optional<String> strict = extractTimeStrict(text);
    if (strict.isPresent()) return strict;

    String normalized = TextNormalizer.normalize(text);

    Matcher hSuffix = TIME_H_SUFFIX.matcher(normalized);
    if (hSuffix.find()) {
      int hour = Integer.parseInt(hSuffix.group(1));
      int minute = hSuffix.group(2) == null ? 0 : Integer.parseInt(hSuffix.group(2));
      Optional<String> resolved = toTimeString(hour, minute);
      if (resolved.isPresent()) return resolved;
    }

    Matcher digitPeriod = TIME_DIGIT_PERIOD.matcher(normalized);
    if (digitPeriod.find()) {
      int hour = applyPeriodOffset(Integer.parseInt(digitPeriod.group(1)), digitPeriod.group(2));
      Optional<String> resolved = toTimeString(hour, 0);
      if (resolved.isPresent()) return resolved;
    }

    Optional<String> wordExtenso = extractTimeFromNumberWord(normalized);
    if (wordExtenso.isPresent()) return wordExtenso;

    Matcher preposition = TIME_PREPOSITION.matcher(normalized);
    if (preposition.find()) {
      int hour = Integer.parseInt(preposition.group(1));
      return toTimeString(hour, 0);
    }

    return Optional.empty();
  }

  /**
   * Casa números por extenso ("cinco", "meio-dia" não incluso) apenas quando
   * acompanhados de uma âncora clara — "horas"/"hs" ou período do dia — para não
   * confundir números soltos em frases sem relação com horário.
   */
  private static Optional<String> extractTimeFromNumberWord(String normalized) {
    boolean hasHourAnchor = normalized.contains("hora");
    Matcher period = Pattern.compile("\\bda\\s+(manha|tarde|noite)\\b").matcher(normalized);
    String periodWord = period.find() ? period.group(1) : null;
    if (!hasHourAnchor && periodWord == null) return Optional.empty();

    for (Map.Entry<String, Integer> entry : NUMBER_WORDS.entrySet()) {
      Matcher wordMatcher = Pattern.compile("\\b" + entry.getKey() + "\\b").matcher(normalized);
      if (wordMatcher.find()) {
        int hour = applyPeriodOffset(entry.getValue(), periodWord);
        return toTimeString(hour, 0);
      }
    }
    return Optional.empty();
  }

  private static int applyPeriodOffset(int hour, String periodWord) {
    if (periodWord == null) return hour;
    if (("tarde".equals(periodWord) || "noite".equals(periodWord)) && hour < 12) {
      return hour + 12;
    }
    return hour;
  }

  private static Optional<String> toTimeString(int hour, int minute) {
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
