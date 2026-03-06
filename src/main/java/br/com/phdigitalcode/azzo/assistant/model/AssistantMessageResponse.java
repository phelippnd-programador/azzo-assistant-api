package br.com.phdigitalcode.azzo.assistant.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class AssistantMessageResponse {
  public String reply;
  public String stage;
  public Map<String, Object> slots = new LinkedHashMap<>();
}
