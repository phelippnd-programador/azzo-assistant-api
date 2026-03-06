package br.com.phdigitalcode.azzo.assistant.infrastructure.client.dto;

public class ClienteCreateDto {
  public String name;
  public String phone;
  public String email;
  /** Usado como chave de deduplicação no backend (telefone ou email normalizado). */
  public String identifier;

  public ClienteCreateDto() {}

  public ClienteCreateDto(String name, String phone, String email, String identifier) {
    this.name = name;
    this.phone = phone;
    this.email = email;
    this.identifier = identifier;
  }
}
