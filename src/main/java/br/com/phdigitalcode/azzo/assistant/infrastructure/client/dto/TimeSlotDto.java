package br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto;

public class TimeSlotDto {
  public String startTime;
  public String endTime;
  /**
   * O endpoint /available-slots só retorna slots disponíveis, portanto este campo
   * pode não vir no JSON. Default true para não filtrar todos os slots na deserialização.
   */
  public boolean available = true;
  /**
   * Score de otimização retornado pelo backend (maior = melhor horário para agenda).
   * Usado para ordenar e limitar os horários sugeridos ao usuário no WhatsApp.
   * Default 0 caso o campo não venha no JSON.
   */
  public int optimizationScore = 0;
}
