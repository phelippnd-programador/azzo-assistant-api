package br.com.phdigitalcode.azzo.assistant.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import br.com.phdigitalcode.azzo.assistant.classifier.OpenNLPIntentClassifier;
import br.com.phdigitalcode.azzo.assistant.dialogue.ConversationData;
import br.com.phdigitalcode.azzo.assistant.domain.entity.ConversationStateEntity;
import br.com.phdigitalcode.azzo.assistant.extractor.ProfessionalNameFinder;
import br.com.phdigitalcode.azzo.assistant.extractor.ServiceNameFinder;
import br.com.phdigitalcode.azzo.assistant.infrastructure.tenant.ContextoTenant;
import br.com.phdigitalcode.azzo.assistant.llm.AgentSystemPromptBuilder;
import br.com.phdigitalcode.azzo.assistant.llm.LlmBookingAgent;
import br.com.phdigitalcode.azzo.assistant.model.IntentPrediction;
import br.com.phdigitalcode.azzo.assistant.model.IntentType;

@ExtendWith(MockitoExtension.class)
class AssistantConversationServiceCostControlUnitTest {

  @Mock OpenNLPIntentClassifier intentClassifier;
  @Mock ServiceNameFinder serviceNameFinder;
  @Mock ProfessionalNameFinder professionalNameFinder;
  @Mock AssistantDomainService domainService;
  @Mock ConversationStateManager stateManager;
  @Mock ContextoTenant contextoTenant;
  @Mock AgentSystemPromptBuilder agentSystemPromptBuilder;
  @Mock LlmBookingAgent llmBookingAgent;

  @InjectMocks
  AssistantConversationService service;

  private static final String USER_ID = "+5511999990001";

  @BeforeEach
  void setUp() throws Exception {
    setPrivateField("agentEnabled", true);
    setPrivateField("ttlMinutes", 120L);
    setPrivateField("llmMaxInputChars", 160);
    setPrivateField("shortResponseMaxTokens", 48);

    UUID tenantId = UUID.randomUUID();
    when(contextoTenant.obterTenantIdOuFalhar()).thenReturn(tenantId);
    when(agentSystemPromptBuilder.build(anyString())).thenReturn("prompt-base");
    when(stateManager.toJson(any(ConversationData.class))).thenReturn("{}");

    ConversationStateEntity entity = new ConversationStateEntity();
    entity.tenantId = tenantId;
    entity.userIdentifier = USER_ID;
    entity.stateJson = "{}";
    entity.updatedAt = Instant.now();

    when(stateManager.loadOrCreate(any(), anyString(), any())).thenReturn(entity);
    when(stateManager.parseState("{}")).thenReturn(new ConversationData());
    when(llmBookingAgent.chat(anyString(), anyList(), anyString(), any(), any()))
        .thenReturn(new LlmBookingAgent.AgentResult("Oi", List.of(), "GROQ"));
  }

  @Test
  void deveUsarModoCurtoParaPerguntaPadrao() {
    when(intentClassifier.classifyWithConfidence(anyString()))
        .thenReturn(new IntentPrediction(IntentType.UNKNOWN, 0.93d));

    service.process("qual o horario de funcionamento?", USER_ID, "Phelipp");

    ArgumentCaptor<LlmBookingAgent.AgentChatOptions> optionsCaptor =
        ArgumentCaptor.forClass(LlmBookingAgent.AgentChatOptions.class);
    verify(llmBookingAgent).chat(anyString(), anyList(), anyString(), any(), optionsCaptor.capture());

    LlmBookingAgent.AgentChatOptions options = optionsCaptor.getValue();
    assertNotNull(options);
    assertEquals(48, options.maxTokens());
    assertTrue(options.runtimeInstruction().contains("1 frase curta"));
  }

  @Test
  void deveCompactarMensagemMuitoLongaAntesDeEnviarAoLlm() {
    when(intentClassifier.classifyWithConfidence(anyString()))
        .thenReturn(new IntentPrediction(IntentType.BOOK, 0.95d));

    String longMessage = "quero agendar ".repeat(80);
    service.process(longMessage, USER_ID, "Phelipp");

    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(llmBookingAgent).chat(anyString(), anyList(), messageCaptor.capture(), any(), any());

    String compacted = messageCaptor.getValue();
    assertNotNull(compacted);
    assertTrue(compacted.length() < longMessage.length());
    assertTrue(compacted.length() <= 300);
    assertTrue(compacted.contains("Mensagem longa truncada pelo sistema"));
  }

  private void setPrivateField(String fieldName, Object value) throws Exception {
    Field field = AssistantConversationService.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(service, value);
  }
}
