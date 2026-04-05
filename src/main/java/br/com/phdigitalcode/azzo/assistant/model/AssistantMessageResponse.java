package br.com.phdigitalcode.azzo.assistant.model;

import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import java.util.LinkedHashMap;
import java.util.Map;

public class AssistantMessageResponse {
  public String reply;
  public ConversationStage stage;
  public Map<String, Object> slots = new LinkedHashMap<>();
}
