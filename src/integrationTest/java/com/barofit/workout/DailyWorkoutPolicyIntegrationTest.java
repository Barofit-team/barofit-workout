package com.barofit.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.barofit.WorkoutApplication;
import com.barofit.common.error.BusinessException;
import com.barofit.testsupport.BarofitJwtKeyTestSupport;
import com.barofit.testsupport.BarofitPostgresTestSupport;
import com.barofit.workout.WorkoutDtos.SyncRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    classes = WorkoutApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({BarofitPostgresTestSupport.class, BarofitJwtKeyTestSupport.class})
class DailyWorkoutPolicyIntegrationTest {
  @Autowired WorkoutSessionService service;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean RoutineSnapshotClient routines;
  UUID routineId;
  JsonNode snapshot;
  Instant start;

  @BeforeEach
  void setup() {
    routineId = UUID.randomUUID();
    snapshot =
        json.readTree(
            "{\"id\":\""
                + routineId
                + "\",\"type\":\"USER\",\"exercises\":[{\"sets\":[{\"id\":\""
                + UUID.randomUUID()
                + "\"}]}]}");
    start =
        LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
    when(routines.fetch(any(), anyString(), anyString())).thenReturn(snapshot);
  }

  SyncRequest request(SessionStatus status, long revision, Instant at) {
    return new SyncRequest(
        routineId,
        snapshot,
        status,
        revision,
        at,
        status == SessionStatus.COMPLETED || status == SessionStatus.ABANDONED
            ? at.plusSeconds(60)
            : null,
        0,
        List.of());
  }

  @Test
  void stopThenSwitchAndCompleteOnlyOnceWithIdempotentReplay() {
    var user = UUID.randomUUID();
    var a = UUID.randomUUID();
    var b = UUID.randomUUID();
    service.sync(user, a, request(SessionStatus.ACTIVE, 0, start), "test-token");
    service.sync(user, a, request(SessionStatus.PAUSED, 1, start), "test-token");
    assertThatThrownBy(
            () -> service.sync(user, b, request(SessionStatus.ACTIVE, 0, start), "test-token"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("먼저 종료");
    service.sync(user, a, request(SessionStatus.ABANDONED, 2, start), "test-token");
    assertThat(service.dailyState(user).completed()).isFalse();
    service.sync(user, b, request(SessionStatus.ACTIVE, 0, start), "test-token");
    var done = request(SessionStatus.COMPLETED, 1, start);
    service.sync(user, b, done, "test-token");
    service.sync(user, b, done, "test-token");
    assertThat(service.dailyState(user).completed()).isTrue();
    assertThatThrownBy(() ->
            service.sync(
                user, UUID.randomUUID(), request(SessionStatus.ACTIVE, 0, start), "test-token"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("이미 완료");
    assertThatThrownBy(() ->
            service.sync(
                user, UUID.randomUUID(), request(SessionStatus.COMPLETED, 0, start), "test-token"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("이미 완료");
    service.sync(
        user,
        UUID.randomUUID(),
        request(SessionStatus.ACTIVE, 0, start.plusSeconds(86400)),
        "test-token");
    service.sync(
        UUID.randomUUID(),
        UUID.randomUUID(),
        request(SessionStatus.ACTIVE, 0, start),
        "test-token");
  }

  @Test
  void simultaneousStartsHaveExactlyOneWinner() throws Exception {
    var user = UUID.randomUUID();
    var gate = new CountDownLatch(1);
    try (var workers = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Boolean> attempt =
          () -> {
            gate.await();
            try {
              service.sync(
                  user, UUID.randomUUID(), request(SessionStatus.ACTIVE, 0, start), "test-token");
              return true;
            } catch (BusinessException rejected) {
              return false;
            }
          };
      var first = workers.submit(attempt);
      var second = workers.submit(attempt);
      gate.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM workout.workout_session WHERE user_id=? AND status='ACTIVE'",
                  Integer.class,
                  user))
          .isEqualTo(1);
    }
  }
}
