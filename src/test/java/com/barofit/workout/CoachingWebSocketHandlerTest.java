package com.barofit.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class CoachingWebSocketHandlerTest {
  @Test
  void echoesRequestIdWithAudio() throws Exception {
    verifyCorrelation(false);
  }

  @Test
  void echoesRequestIdWhenSynthesisFails() throws Exception {
    verifyCorrelation(true);
  }

  private void verifyCorrelation(boolean failSynthesis) throws Exception {
    var json = new ObjectMapper();
    var sessions = mock(WorkoutSessionService.class);
    var audio = mock(ElevenLabsClient.class);
    var feedback = mock(OpenAiFeedbackClient.class);
    var socket = mock(WebSocketSession.class);
    var user = UUID.randomUUID();
    var session = UUID.randomUUID();
    var attributes = new HashMap<String, Object>();
    attributes.put("userId", user);
    attributes.put("sessionId", session);
    attributes.put("coachingStyle", CoachingStyle.CALM);
    attributes.put("coachingSequence", new AtomicLong());
    attributes.put("gptInFlight", new AtomicBoolean());
    when(socket.getAttributes()).thenReturn(attributes);
    when(socket.isOpen()).thenReturn(true);
    when(sessions.get(user, session))
        .thenReturn(
            new WorkoutDtos.SessionResponse(
                session,
                UUID.randomUUID(),
                json.readTree("{}"),
                SessionStatus.ACTIVE,
                0,
                Instant.now(),
                null,
                0,
                List.of(),
                0));
    if (failSynthesis)
      when(audio.synthesize(anyString())).thenThrow(new IllegalStateException("unavailable"));
    else when(audio.synthesize(anyString())).thenReturn(new byte[] {1, 2});
    var received = new CountDownLatch(1);
    var response = new java.util.concurrent.atomic.AtomicReference<String>();
    doAnswer(
            invocation -> {
              response.set(((TextMessage) invocation.getArgument(0)).getPayload());
              received.countDown();
              return null;
            })
        .when(socket)
        .sendMessage(any(TextMessage.class));
    var handler =
        new CoachingWebSocketHandler(
            mock(JwtDecoder.class),
            sessions,
            audio,
            mock(CoachingProfileClient.class),
            feedback,
            json);
    try {
      handler.handleTextMessage(
          socket,
          new TextMessage(
              "{\"type\":\"COACH_EVENT\",\"eventType\":\"WORKOUT_START\",\"requestId\":\"phase-2-request-7\"}"));
      assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
      var payload = json.readTree(response.get());
      assertThat(payload.path("requestId").asText()).isEqualTo("phase-2-request-7");
      assertThat(payload.path("type").asText()).isEqualTo("WORKOUT_START");
      assertThat(payload.path("text").asText()).isNotBlank();
      assertThat(payload.has("audioBase64")).isEqualTo(!failSynthesis);
    } finally {
      handler.shutdown();
    }
  }
}
