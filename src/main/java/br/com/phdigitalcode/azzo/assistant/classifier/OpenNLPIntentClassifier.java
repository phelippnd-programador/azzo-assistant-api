package br.com.phdigitalcode.azzo.assistant.classifier;

import java.util.Locale;

import br.com.phdigitalcode.azzo.assistant.model.IntentPrediction;
import br.com.phdigitalcode.azzo.assistant.model.IntentType;
import br.com.phdigitalcode.azzo.assistant.training.OpenNLPModelTrainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import opennlp.tools.doccat.DocumentCategorizerME;
import opennlp.tools.tokenize.SimpleTokenizer;

@ApplicationScoped
public class OpenNLPIntentClassifier {

  @Inject
  OpenNLPModelTrainer modelTrainer;

  public IntentType classify(String message) {
    return classifyWithConfidence(message).intent;
  }

  public IntentPrediction classifyWithConfidence(String message) {
    if (message == null || message.isBlank()) {
      return IntentPrediction.unknown();
    }

    String[] tokens = SimpleTokenizer.INSTANCE.tokenize(message.toLowerCase(Locale.ROOT));
    if (tokens.length == 0) {
      return IntentPrediction.unknown();
    }

    DocumentCategorizerME categorizer = new DocumentCategorizerME(modelTrainer.getIntentModel());
    double[] outcomes = categorizer.categorize(tokens);
    String best = normalizeCategory(categorizer.getBestCategory(outcomes));
    double confidence = 0.0d;
    for (int i = 0; i < outcomes.length; i++) {
      String category = normalizeCategory(categorizer.getCategory(i));
      if (best.equalsIgnoreCase(category)) {
        confidence = outcomes[i];
        break;
      }
    }

    try {
      IntentType intent = IntentType.valueOf(best.toUpperCase(Locale.ROOT));
      return new IntentPrediction(intent, confidence);
    } catch (IllegalArgumentException e) {
      return new IntentPrediction(IntentType.UNKNOWN, confidence);
    }
  }

  private String normalizeCategory(String rawCategory) {
    if (rawCategory == null) return "";
    String value = rawCategory.trim();
    if (value.regionMatches(true, 0, "__label__", 0, "__label__".length())) {
      return value.substring("__label__".length());
    }
    return value;
  }
}
