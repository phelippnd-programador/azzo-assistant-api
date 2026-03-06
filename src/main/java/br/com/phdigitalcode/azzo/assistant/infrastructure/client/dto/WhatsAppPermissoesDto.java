package br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto;

public class WhatsAppPermissoesDto {
  public boolean canSchedule;
  public boolean canCancel;
  public boolean canReschedule;

  public WhatsAppPermissoesDto() {}

  public WhatsAppPermissoesDto(boolean canSchedule, boolean canCancel, boolean canReschedule) {
    this.canSchedule = canSchedule;
    this.canCancel = canCancel;
    this.canReschedule = canReschedule;
  }
}
