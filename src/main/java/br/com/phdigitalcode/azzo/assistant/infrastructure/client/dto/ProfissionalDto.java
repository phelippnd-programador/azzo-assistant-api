package br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto;

import java.util.List;

public class ProfissionalDto {
  public String id;
  public String name;
  public String email;
  public String phone;
  public double commissionRate;
  public boolean isActive;
  /** Especialidades com descrição — populado pelo agenda-pro e usado para enriquecer o assistente. */
  public List<SpecialidadeInfoDto> specialtiesDetailed;

  public static class SpecialidadeInfoDto {
    public String name;
    public String description;
  }
}
