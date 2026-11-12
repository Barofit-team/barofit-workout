package com.barofit.workout;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.barofit.WorkoutApplication;
import com.barofit.testsupport.BarofitJwtKeyTestSupport;
import com.barofit.testsupport.BarofitPostgresTestSupport;
import com.barofit.testsupport.BarofitTestTokens;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컨트롤러·라우팅·인증 계층의 HTTP 통합 테스트. 서비스 계층 비즈니스 로직은 {@link WorkoutSessionServiceTest}가 담당하므로 여기서는 중복하지
 * 않는다.
 */
@SpringBootTest(classes = WorkoutApplication.class)
@AutoConfigureMockMvc
@Import({BarofitPostgresTestSupport.class, BarofitJwtKeyTestSupport.class})
@Transactional
class WorkoutSessionControllerHttpTest {

  @Autowired MockMvc mvc;
  @MockitoBean RoutineSnapshotClient routines;

  private static String bearer(UUID user) {
    return "Bearer " + BarofitTestTokens.accessToken(user, Instant.now());
  }

  @Test
  void syncAndGetRequireAuthentication() throws Exception {
    UUID sessionId = UUID.randomUUID();
    mvc.perform(
            put("/api/v1/workout-sessions/" + sessionId)
                .contentType(APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/workout-sessions/" + sessionId)).andExpect(status().isUnauthorized());
  }

  @Test
  void syncCreatesSessionAndGetReturnsItThroughHttp() throws Exception {
    UUID user = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID routineId = UUID.randomUUID();
    String token = bearer(user);
    String accessToken = token.substring("Bearer ".length());
    UUID setId = UUID.randomUUID();
    String snapshot =
        """
        {"id":"%s","type":"USER","exercises":[{"exerciseId":"push-up","sets":[{"id":"%s"}]}]}
        """
            .formatted(routineId, setId);
    when(routines.fetch(eq(routineId), eq("USER"), eq(accessToken)))
        .thenReturn(new tools.jackson.databind.ObjectMapper().readTree(snapshot));

    String request =
        """
        {"routineId":"%s","routineSnapshot":%s,"status":"ACTIVE","revision":0,
         "startedAt":"2026-10-20T09:00:00Z","activeDurationSeconds":0,"sets":[]}
        """
            .formatted(routineId, snapshot);

    mvc.perform(
            put("/api/v1/workout-sessions/" + sessionId)
                .header("Authorization", token)
                .contentType(APPLICATION_JSON)
                .content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(sessionId.toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"));

    mvc.perform(get("/api/v1/workout-sessions/" + sessionId).header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.routineId").value(routineId.toString()));
  }

  @Test
  void getReturnsNotFoundForSessionOwnedByAnotherUser() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID stranger = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    UUID routineId = UUID.randomUUID();
    String ownerToken = bearer(owner);
    String ownerAccessToken = ownerToken.substring("Bearer ".length());
    UUID setId = UUID.randomUUID();
    String snapshot =
        """
        {"id":"%s","type":"USER","exercises":[{"exerciseId":"push-up","sets":[{"id":"%s"}]}]}
        """
            .formatted(routineId, setId);
    when(routines.fetch(eq(routineId), eq("USER"), eq(ownerAccessToken)))
        .thenReturn(new tools.jackson.databind.ObjectMapper().readTree(snapshot));
    String request =
        """
        {"routineId":"%s","routineSnapshot":%s,"status":"ACTIVE","revision":0,
         "startedAt":"2026-10-20T09:00:00Z","activeDurationSeconds":0,"sets":[]}
        """
            .formatted(routineId, snapshot);
    mvc.perform(
            put("/api/v1/workout-sessions/" + sessionId)
                .header("Authorization", ownerToken)
                .contentType(APPLICATION_JSON)
                .content(request))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/v1/workout-sessions/" + sessionId).header("Authorization", bearer(stranger)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WORKOUT_NOT_FOUND"));
  }

  @Test
  void weeklyProgressAndStreakReturnEmptyDefaultsForNewUser() throws Exception {
    String token = bearer(UUID.randomUUID());
    LocalDate weekStart = LocalDate.now().minusDays(LocalDate.now().getDayOfWeek().getValue() - 1);
    mvc.perform(
            get("/api/v1/workout-sessions/weekly-progress")
                .header("Authorization", token)
                .param("weekStart", weekStart.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completedDates").isEmpty());
    mvc.perform(get("/api/v1/workout-sessions/streak").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.streakDays").value(0));
  }

  @Test
  void historyRejectsRangeLongerThan31Days() throws Exception {
    String token = bearer(UUID.randomUUID());
    mvc.perform(
            get("/api/v1/workout-sessions/history")
                .header("Authorization", token)
                .param("startDate", "2026-01-01")
                .param("endDate", "2026-03-01"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_WORKOUT"));
  }
}
