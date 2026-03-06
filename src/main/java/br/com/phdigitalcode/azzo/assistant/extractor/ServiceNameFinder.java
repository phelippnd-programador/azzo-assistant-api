package br.com.phdigitalcode.azzo.assistant.extractor;

import java.util.Optional;

import br.com.phdigitalcode.azzo.assistant.training.OpenNLPModelTrainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.tokenize.SimpleTokenizer;

@ApplicationScoped
public class ServiceNameFinder {

  @Inject
  OpenNLPModelTrainer modelTrainer;

  public Optional<String> extractFirst(String message) {
    if (message == null || message.isBlank()) return Optional.empty();
    String[] tokens = SimpleTokenizer.INSTANCE.tokenize(message);
    if (tokens.length == 0) return Optional.empty();

    NameFinderME finder = new NameFinderME(modelTrainer.getServiceModel());
    return NameFinderSupport.firstMatch(tokens, finder.find(tokens));
  }
}
