package br.com.phdigitalcode.azzo.assistant.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AssistantMessageResponseJsonUnitTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldSerializeStageEnumAsString() throws Exception {
    AssistantMessageResponse response = new AssistantMessageResponse();
    response.reply = "ok";
    response.stage = ConversationStage.ASK_SERVICE;

    String json = objectMapper.writeValueAsString(response);

    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> payload = objectMapper.readValue(json, java.util.Map.class);
    assertEquals("ASK_SERVICE", payload.get("stage"));
  }
}
