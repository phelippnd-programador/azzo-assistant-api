package br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto;

import java.util.ArrayList;
import java.util.List;

public class AgendamentoCreateDto {
  public String serviceId;
  public List<ItemDto> items = new ArrayList<>();
  public String professionalId;
  public String clientId;
  public String date;
  public String startTime;
  public String status;
  public String notes;

  public static class ItemDto {
    public String serviceId;
    public int quantity = 1;
    public long unitPrice;
    public long totalPrice;
  }
}
