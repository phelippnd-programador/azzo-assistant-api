package br.com.phdigitalcode.azzo.assistant.extractor;

import java.util.Optional;

import opennlp.tools.util.Span;

public final class NameFinderSupport {

  private NameFinderSupport() {}

  public static Optional<String> firstMatch(String[] tokens, Span[] spans) {
    if (tokens == null || spans == null || spans.length == 0) return Optional.empty();
    Span first = spans[0];
    StringBuilder result = new StringBuilder();
    for (int i = first.getStart(); i < first.getEnd(); i++) {
      if (i > first.getStart()) result.append(' ');
      result.append(tokens[i]);
    }
    return Optional.of(result.toString());
  }
}
