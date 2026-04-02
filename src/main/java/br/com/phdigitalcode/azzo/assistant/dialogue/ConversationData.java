package br.com.phdigitalcode.azzo.assistant.dialogue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ConversationData {
  public ConversationStage stage = ConversationStage.START;
  public UUID serviceId;
  public UUID professionalId;
  public LocalDate date;
  public String time;
  public UUID appointmentId;
  public UUID sourceAppointmentId;
  public UUID reactivationCycleId;
  /** ID do agendamento selecionado para cancelar, aguardando confirmação do usuário. */
  public UUID pendingCancelAppointmentId;
  public String serviceName;
  public String professionalName;
  /** Nome da primeira especialidade do profissional — exibido na confirmação e na listagem. */
  public String professionalSpecialtyName;
  public String customerName;
  public String userIdentifier;
  public ConversationStage reactivationResumeStage;
  public String reactivationLastPrompt;
  public boolean manualInterventionSuggested;
  public String manualInterventionReason;
  public Integer manualInterventionAttempts;
  public TimePeriod preferredPeriod;
  public List<String> professionalOptionIds = new ArrayList<>();
  public List<String> professionalOptionNames = new ArrayList<>();
  /** Primeira especialidade de cada profissional listado — índice alinhado com professionalOptionIds. */
  public List<String> professionalOptionSpecialtyNames = new ArrayList<>();
  public List<String> availableTimeOptions = new ArrayList<>();
  public List<String> appointmentOptionIds = new ArrayList<>();
  public List<String> appointmentOptionLabels = new ArrayList<>();
  public int stageAttempts = 0;
  /** Histórico completo da conversa enviado ao LLM a cada turno. */
  public List<ChatMessage> chatHistory = new ArrayList<>();
  /**
   * Provider LLM ativo nesta conversa: "GROQ" | "OLLAMA" | null (nova conversa).
   * Sticky: uma vez definido, mantém o mesmo provider até a conversa terminar.
   */
  public String activeProvider;

  public void reset() {
    stage = ConversationStage.START;
    serviceId = null;
    professionalId = null;
    date = null;
    time = null;
    appointmentId = null;
    sourceAppointmentId = null;
    reactivationCycleId = null;
    pendingCancelAppointmentId = null;
    serviceName = null;
    professionalName = null;
    professionalSpecialtyName = null;
    customerName = null;
    reactivationResumeStage = null;
    reactivationLastPrompt = null;
    manualInterventionSuggested = false;
    manualInterventionReason = null;
    manualInterventionAttempts = null;
    preferredPeriod = null;
    professionalOptionIds.clear();
    professionalOptionNames.clear();
    professionalOptionSpecialtyNames.clear();
    availableTimeOptions.clear();
    appointmentOptionIds.clear();
    appointmentOptionLabels.clear();
    stageAttempts = 0;
    chatHistory.clear();
    activeProvider = null;
  }
}
