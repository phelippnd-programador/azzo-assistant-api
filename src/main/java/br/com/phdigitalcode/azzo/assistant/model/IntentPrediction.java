package br.com.phdigitalcode.azzo.assistant.model;

public class IntentPrediction {
  public final IntentType intent;
  public final double confidence;

  public IntentPrediction(IntentType intent, double confidence) {
    this.intent = intent;
    this.confidence = confidence;
  }

  public static IntentPrediction unknown() {
    return new IntentPrediction(IntentType.UNKNOWN, 0.0d);
  }
}
