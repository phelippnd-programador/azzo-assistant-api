package br.com.phdigitalcode.azzo.assistant.model;

import jakarta.validation.constraints.NotBlank;

public class AssistantMessageRequest {
  @NotBlank
  public String message;
}
