package br.com.phdigitalcode.azzo.assistant.training;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import opennlp.tools.doccat.DoccatFactory;
import opennlp.tools.doccat.DoccatModel;
import opennlp.tools.doccat.DocumentCategorizerME;
import opennlp.tools.doccat.DocumentSample;
import opennlp.tools.doccat.DocumentSampleStream;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.NameSample;
import opennlp.tools.namefind.NameSampleDataStream;
import opennlp.tools.namefind.TokenNameFinderFactory;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.util.InputStreamFactory;
import opennlp.tools.util.MarkableFileInputStreamFactory;
import opennlp.tools.util.ObjectStream;
import opennlp.tools.util.PlainTextByLineStream;
import opennlp.tools.util.TrainingParameters;

@Startup
@ApplicationScoped
public class OpenNLPModelTrainer {

  private static final Logger LOG = Logger.getLogger(OpenNLPModelTrainer.class);

  private static final String INTENT_TRAIN_FILE = "intent-train.txt";
  private static final String SERVICE_NER_TRAIN_FILE = "service-ner-train.txt";
  private static final String PROFESSIONAL_NER_TRAIN_FILE = "professional-ner-train.txt";

  private static final String INTENT_MODEL_FILE = "intent-model.bin";
  private static final String SERVICE_NER_MODEL_FILE = "service-ner-model.bin";
  private static final String PROFESSIONAL_NER_MODEL_FILE = "professional-ner-model.bin";
  private static final Charset[] READ_FALLBACK_CHARSETS = new Charset[] {
      StandardCharsets.UTF_8,
      Charset.forName("windows-1252"),
      StandardCharsets.ISO_8859_1
  };

  @ConfigProperty(name = "assistant.ai.training-dir", defaultValue = "src/main/resources/ai/training")
  String trainingDir;

  @ConfigProperty(name = "assistant.ai.models-dir", defaultValue = "src/main/resources/ai/models")
  String modelsDir;

  private volatile DoccatModel intentModel;
  private volatile TokenNameFinderModel serviceModel;
  private volatile TokenNameFinderModel professionalModel;

  @PostConstruct
  void init() {
    try {
      ensureTrainingCorpus();
      ensureModels(false);
      LOG.info("OpenNLP inicializado com sucesso");
    } catch (Exception e) {
      LOG.error("Falha ao inicializar modelos OpenNLP", e);
      throw new IllegalStateException("Nao foi possivel inicializar OpenNLP", e);
    }
  }

  public synchronized void forceRetrain() {
    try {
      ensureTrainingCorpus();
      ensureModels(true);
      LOG.info("Retreinamento OpenNLP concluido");
    } catch (Exception e) {
      LOG.error("Falha ao retreinar modelos OpenNLP", e);
      throw new IllegalStateException("Nao foi possivel retreinar OpenNLP", e);
    }
  }

  public DoccatModel getIntentModel() {
    return intentModel;
  }

  public TokenNameFinderModel getServiceModel() {
    return serviceModel;
  }

  public TokenNameFinderModel getProfessionalModel() {
    return professionalModel;
  }

  private void ensureModels(boolean forceRetrain) throws IOException {
    Path modelBase = Path.of(modelsDir);
    Path trainingBase = Path.of(trainingDir);
    Files.createDirectories(modelBase);

    Path intentModelPath = modelBase.resolve(INTENT_MODEL_FILE);
    Path serviceModelPath = modelBase.resolve(SERVICE_NER_MODEL_FILE);
    Path professionalModelPath = modelBase.resolve(PROFESSIONAL_NER_MODEL_FILE);

    if (forceRetrain || !Files.exists(intentModelPath)) {
      trainIntentModel(intentModelPath);
    }
    if (forceRetrain || !Files.exists(serviceModelPath)) {
      trainNerModel(trainingBase.resolve(SERVICE_NER_TRAIN_FILE), serviceModelPath, "service");
    }
    if (forceRetrain || !Files.exists(professionalModelPath)) {
      trainNerModel(trainingBase.resolve(PROFESSIONAL_NER_TRAIN_FILE), professionalModelPath, "professional");
    }

    intentModel = new DoccatModel(intentModelPath);
    serviceModel = new TokenNameFinderModel(serviceModelPath);
    professionalModel = new TokenNameFinderModel(professionalModelPath);
  }

  private void trainIntentModel(Path outputModelPath) throws IOException {
    Path trainPath = Path.of(trainingDir).resolve(INTENT_TRAIN_FILE);
    InputStreamFactory dataIn = new MarkableFileInputStreamFactory(trainPath.toFile());
    try (ObjectStream<String> lineStream = new PlainTextByLineStream(dataIn, StandardCharsets.UTF_8);
        ObjectStream<DocumentSample> sampleStream = new DocumentSampleStream(lineStream)) {
      TrainingParameters params = new TrainingParameters();
      params.put(TrainingParameters.ITERATIONS_PARAM, "120");
      params.put(TrainingParameters.CUTOFF_PARAM, "1");
      DoccatModel model = DocumentCategorizerME.train("pt", sampleStream, params, new DoccatFactory());
      try (OutputStream out = Files.newOutputStream(outputModelPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
        model.serialize(out);
      }
    }
  }

  private void trainNerModel(Path trainPath, Path outputModelPath, String type) throws IOException {
    InputStreamFactory dataIn = new MarkableFileInputStreamFactory(trainPath.toFile());
    try (ObjectStream<String> lineStream = new PlainTextByLineStream(dataIn, StandardCharsets.UTF_8);
        ObjectStream<NameSample> sampleStream = new NameSampleDataStream(lineStream)) {
      TrainingParameters params = new TrainingParameters();
      params.put(TrainingParameters.ITERATIONS_PARAM, "100");
      params.put(TrainingParameters.CUTOFF_PARAM, "1");
      TokenNameFinderModel model =
          NameFinderME.train("pt", type, sampleStream, params, new TokenNameFinderFactory());
      try (OutputStream out = Files.newOutputStream(outputModelPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
        model.serialize(out);
      }
    }
  }

  private void ensureTrainingCorpus() throws IOException {
    Path trainingBase = Path.of(trainingDir);
    Path modelBase = Path.of(modelsDir);
    Files.createDirectories(trainingBase);
    Files.createDirectories(modelBase);

    Path intentPath = trainingBase.resolve(INTENT_TRAIN_FILE);
    Path servicePath = trainingBase.resolve(SERVICE_NER_TRAIN_FILE);
    Path professionalPath = trainingBase.resolve(PROFESSIONAL_NER_TRAIN_FILE);

    upsertCorpusLines(intentPath, defaultIntentLines(), "intencoes");
    if (shouldRewriteNerCorpus(servicePath)) {
      Files.write(servicePath, defaultServiceNerLines(), StandardCharsets.UTF_8);
      LOG.infof("Corpus NER service criado: %s", servicePath);
    }
    if (shouldRewriteNerCorpus(professionalPath)) {
      Files.write(professionalPath, defaultProfessionalNerLines(), StandardCharsets.UTF_8);
      LOG.infof("Corpus NER professional criado: %s", professionalPath);
    }
  }

  private void upsertCorpusLines(Path path, List<String> defaults, String label) throws IOException {
    Set<String> merged = new LinkedHashSet<>();
    if (Files.exists(path) && Files.size(path) > 0) {
      for (String line : readAllLinesWithFallback(path)) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.isEmpty()) merged.add(trimmed);
      }
    }
    merged.addAll(defaults);

    List<String> finalLines = new ArrayList<>(merged);
    if (!Files.exists(path) || Files.size(path) == 0) {
      Files.write(path, finalLines, StandardCharsets.UTF_8);
      LOG.infof("Corpus %s criado: %s", label, path);
      return;
    }

    List<String> currentLines = readAllLinesWithFallback(path);
    if (!currentLines.equals(finalLines)) {
      Files.write(path, finalLines, StandardCharsets.UTF_8);
      LOG.infof("Corpus %s atualizado com novas frases: %s", label, path);
    }
  }

  private boolean shouldRewriteNerCorpus(Path path) throws IOException {
    if (!Files.exists(path) || Files.size(path) == 0) return true;
    String content = readStringWithFallback(path);
    // Garante formato esperado para NameSampleDataStream: "<START:type> valor <END>"
    return !content.contains("<START:") || !content.contains(" <END>");
  }

  private List<String> readAllLinesWithFallback(Path path) throws IOException {
    for (Charset charset : READ_FALLBACK_CHARSETS) {
      try {
        List<String> lines = Files.readAllLines(path, charset);
        if (!StandardCharsets.UTF_8.equals(charset)) {
          Files.write(path, lines, StandardCharsets.UTF_8);
          LOG.warnf("Corpus %s convertido para UTF-8 (origem: %s)", path, charset.name());
        }
        return lines;
      } catch (MalformedInputException e) {
        // Tenta o proximo charset
      }
    }
    throw new IOException("Nao foi possivel ler arquivo com os charsets suportados: " + path);
  }

  private String readStringWithFallback(Path path) throws IOException {
    for (Charset charset : READ_FALLBACK_CHARSETS) {
      try {
        String content = Files.readString(path, charset);
        if (!StandardCharsets.UTF_8.equals(charset)) {
          Files.writeString(path, content, StandardCharsets.UTF_8);
          LOG.warnf("Corpus %s convertido para UTF-8 (origem: %s)", path, charset.name());
        }
        return content;
      } catch (MalformedInputException e) {
        // Tenta o proximo charset
      }
    }
    throw new IOException("Nao foi possivel ler arquivo com os charsets suportados: " + path);
  }

  private List<String> defaultIntentLines() {
    Set<String> lines = new LinkedHashSet<>();
    appendIntent(lines, "BOOK", List.of(
        "quero agendar",
        "preciso marcar horario",
        "agenda um horario pra mim",
        "to querendo marcar",
        "da pra reservar um horario",
        "quero agendar corte",
        "marca pra mim hoje",
        "agendar para amanha",
        "quero um horario com profissional",
        "me ajuda a marcar atendimento",
        "quero continuar meu agendamento",
        "vamos continuar de onde parei",
        "quero fechar esse horario",
        "quero seguir com o agendamento"));
    appendIntent(lines, "CANCEL", List.of(
        "quero cancelar meu horario",
        "cancela meu agendamento",
        "nao vou conseguir ir",
        "desmarca pra mim",
        "preciso cancelar",
        "cancelar minha reserva",
        "remove meu horario",
        "anula meu agendamento",
        "nao quero mais esse horario",
        "pode cancelar o atendimento"));
    appendIntent(lines, "RESCHEDULE", List.of(
        "quero remarcar",
        "preciso mudar o horario",
        "trocar meu agendamento",
        "da pra remarcar",
        "muda pra outro horario",
        "quero alterar a data",
        "pode reagendar",
        "trocar horario de amanha",
        "nao consigo nesse horario, muda",
        "remarca meu atendimento",
        "quero trocar o profissional",
        "troca o profissional pra mim",
        "mudar para outro profissional",
        "quero escolher outro profissional",
        "trocar so o dia",
        "quero mudar o dia",
        "trocar apenas o horario",
        "quero voltar uma etapa",
        "voltar para o passo anterior"));
    appendIntent(lines, "LIST", List.of(
        "quais agendamentos eu tenho",
        "lista meus horarios",
        "me mostra meus agendamentos",
        "tenho horario marcado",
        "consultar agenda",
        "ver minhas reservas",
        "quero ver meus atendimentos",
        "mostra os proximos horarios",
        "meus agendamentos por favor",
        "o que eu tenho marcado"));
    appendIntent(lines, "GREETING", List.of(
        "oi",
        "ola",
        "bom dia",
        "boa tarde",
        "boa noite",
        "e ai",
        "tudo bem",
        "opa",
        "hello",
        "fala comigo",
        "oii",
        "oiii",
        "ola tudo bem",
        "bom dia tudo bem",
        "boa tarde tudo certo",
        "boa noite tudo bem",
        "fala",
        "fala ai",
        "opa tudo bem",
        "oi pessoal",
        "bom dia equipe",
        "olaa",
        "hey",
        "salve",
        "to por aqui",
        "consegue me atender",
        "tem alguem ai",
        "alguem online",
        "atendimento por favor"));
    return new ArrayList<>(lines);
  }

  private void appendIntent(Set<String> lines, String label, List<String> seeds) {
    String[] prefix = new String[] {"", "oi ", "ola ", "opa ", "por favor ", "consegue ", "pode "};
    String[] suffix = new String[] {
        "",
        " por favor",
        " ai",
        " agora",
        " rapidinho",
        " hj",
        " hoje",
        " amanha",
        " por gentileza",
        " vlw",
        " por favr",
        " pfv"
    };
    String[] punctuation = new String[] {"", "!", "!!", ".", "..."};
    for (String base : seeds) {
      for (String b : typoVariants(base)) {
        for (String p : prefix) {
          for (String s : suffix) {
            for (String mark : punctuation) {
              lines.add(label + " " + p + b + s + mark);
            }
          }
        }
      }
    }
  }

  private List<String> typoVariants(String base) {
    Set<String> variants = new LinkedHashSet<>();
    variants.add(base);
    variants.add(base.replace("quero", "qro"));
    variants.add(base.replace("quero", "kero"));
    variants.add(base.replace("horario", "horaro"));
    variants.add(base.replace("horario", "horário"));
    variants.add(base.replace("agendar", "agenda"));
    variants.add(base.replace("agendar", "agendae"));
    variants.add(base.replace("cancelar", "canselar"));
    variants.add(base.replace("remarcar", "remarca"));
    variants.add(base.replace("remarcar", "remacrar"));
    variants.add(base.replace("profissional", "profisiona"));
    variants.add(base.replace("atendimento", "atendimnto"));
    variants.add(base.replace("amanha", "amanhã"));
    variants.add(base.replace("nao", "não"));
    return new ArrayList<>(variants);
  }

  private List<String> defaultServiceNerLines() {
    List<String> services = List.of(
        "corte", "escova", "barba", "sobrancelha", "hidratacao", "coloracao", "manicure", "pedicure", "progressiva", "limpeza de pele", "design de unhas", "botox capilar");
    return buildNerLines(
        "service",
        services,
        List.of(
            "quero %s",
            "quero fazer %s",
            "preciso de %s",
            "tem horario de %s hoje",
            "da pra agendar %s amanha",
            "to querendo %s",
            "me ajuda com %s",
            "agendar %s por favor",
            "consigo %s hoje",
            "pode marcar %s"));
  }

  private List<String> defaultProfessionalNerLines() {
    List<String> professionals = List.of("Ana", "Bruno", "Carla", "Diego", "Eduarda", "Felipe", "Gabriela", "Henrique", "Isabela", "Joao", "Karen", "Lucas");
    return buildNerLines(
        "professional",
        professionals,
        List.of(
            "quero com %s",
            "pode marcar com %s por favor",
            "tem horario com %s hoje",
            "prefiro atendimento da %s",
            "agendar com %s amanha",
            "pode ser com %s",
            "me coloca com %s",
            "atendimento com %s",
            "quero ser atendido por %s",
            "deixa com %s"));
  }

  private List<String> buildNerLines(String entityType, List<String> entities, List<String> templates) {
    Set<String> lines = new LinkedHashSet<>();
    String[] prefixes = new String[] {"", "oi ", "ola ", "por favor ", "opa "};
    String[] suffixes = new String[] {"", " por favor", " ai", " agora", " rapidinho", " hj", " amanha"};
    String[] marks = new String[] {"", "!", "."};

    for (String entity : entities) {
      for (String entityVariant : typoVariants(entity)) {
        String tagged = "<START:" + entityType + "> " + entityVariant + " <END>";
        for (String template : templates) {
          for (String prefix : prefixes) {
            for (String suffix : suffixes) {
              for (String mark : marks) {
                lines.add(prefix + String.format(template, tagged) + suffix + mark);
              }
            }
          }
        }
      }
    }
    return new ArrayList<>(lines);
  }
}
