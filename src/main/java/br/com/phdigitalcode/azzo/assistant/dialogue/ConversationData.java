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
  /** ID do agendamento selecionado para cancelar, aguardando confirmação do usuário. */
  public UUID pendingCancelAppointmentId;
  public String serviceName;
  public String professionalName;
  public String customerName;
  public String userIdentifier;
  public TimePeriod preferredPeriod;
  public List<String> professionalOptionIds = new ArrayList<>();
  public List<String> professionalOptionNames = new ArrayList<>();
  public List<String> availableTimeOptions = new ArrayList<>();
  public List<String> appointmentOptionIds = new ArrayList<>();
  public List<String> appointmentOptionLabels = new ArrayList<>();

  public void reset() {
    stage = ConversationStage.START;
    serviceId = null;
    professionalId = null;
    date = null;
    time = null;
    appointmentId = null;
    sourceAppointmentId = null;
    pendingCancelAppointmentId = null;
    serviceName = null;
    professionalName = null;
    customerName = null;
    preferredPeriod = null;
    professionalOptionIds.clear();
    professionalOptionNames.clear();
    availableTimeOptions.clear();
    appointmentOptionIds.clear();
    appointmentOptionLabels.clear();
  }
}
