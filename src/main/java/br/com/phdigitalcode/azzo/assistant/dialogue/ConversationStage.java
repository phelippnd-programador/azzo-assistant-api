package br.com.phdigitalcode.azzo.assistant.dialogue;

public enum ConversationStage {
  START,
  ASK_CANCEL_APPOINTMENT,
  ASK_RESCHEDULE_APPOINTMENT,
  ASK_NAME,
  ASK_SERVICE,
  ASK_PROFESSIONAL,
  ASK_DATE,
  ASK_PERIOD,
  ASK_TIME,
  CONFIRMATION,
  COMPLETED,
  /**
   * Contexto pré-semeado pelo ReminderScheduler ao enviar o lembrete automático.
   * O assistente aguarda o cliente responder CONFIRMAR ou CANCELAR para um
   * agendamento já existente — sem iniciar um novo fluxo de booking.
   */
  AWAITING_APPOINTMENT_CONFIRMATION,
  AWAITING_REACTIVATION_REPLY
}
