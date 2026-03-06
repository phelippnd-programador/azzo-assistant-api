package br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto;

public class NotificacaoCreateDto {
  public String appointmentId;
  public String channel;
  public String destination;
  public String message;
  public String status;

  public NotificacaoCreateDto() {}

  public NotificacaoCreateDto(String appointmentId, String channel, String destination, String message, String status) {
    this.appointmentId = appointmentId;
    this.channel = channel;
    this.destination = destination;
    this.message = message;
    this.status = status;
  }
}
