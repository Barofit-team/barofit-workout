package com.barofit.workout;

import com.barofit.common.error.BusinessException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class WeeklyWorkoutProgressService {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;

  public WeeklyWorkoutProgressService(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public record Result(LocalDate weekStart, List<LocalDate> completedDates) {}

  public record StreakResult(LocalDate asOf, int streakDays) {}

  public StreakResult currentStreak(UUID userId) {
    LocalDate today = LocalDate.now(SEOUL);
    Map<LocalDate, Set<LocalDate>> cache = new HashMap<>();
    java.util.function.Predicate<LocalDate> completed =
        date -> {
          LocalDate monday =
              date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          return cache
              .computeIfAbsent(monday, key -> new HashSet<>(get(userId, key).completedDates()))
              .contains(date);
        };
    LocalDate cursor = completed.test(today) ? today : today.minusDays(1);
    int count = 0;
    while (completed.test(cursor)) {
      count++;
      cursor = cursor.minusDays(1);
    }
    return new StreakResult(today, count);
  }

  public record WeeklyStreakResult(LocalDate asOf, int streakWeeks) {}

  public WeeklyStreakResult currentWeeklyStreak(UUID userId, int weeklyGoal) {
    if (weeklyGoal < 1 || weeklyGoal > 7)
      throw new BusinessException(WorkoutErrorCode.INVALID_WORKOUT, "주간 목표는 1~7회여야 합니다.");
    LocalDate today = LocalDate.now(SEOUL);
    var dates = new HashSet<LocalDate>();
    jdbc.query(
        """
        SELECT s.ended_at, s.routine_snapshot,
          coalesce(array_agg(r.routine_set_id::text) FILTER (WHERE r.actual_reps > 0 OR r.actual_duration_seconds > 0), ARRAY[]::text[]) AS performed
        FROM workout.workout_session s
        LEFT JOIN workout.workout_set_result r ON r.session_id = s.id
        WHERE s.user_id = ? AND s.status = 'COMPLETED' AND s.ended_at <= now()
        GROUP BY s.id
        """,
        rs -> {
          Set<String> performed = new HashSet<>();
          Object[] values = (Object[]) rs.getArray("performed").getArray();
          for (Object value : values) performed.add(value.toString());
          if (WorkoutCompletionPolicy.qualifies(
              json.readTree(rs.getString("routine_snapshot")), performed))
            dates.add(rs.getTimestamp("ended_at").toInstant().atZone(SEOUL).toLocalDate());
        },
        userId);
    return new WeeklyStreakResult(today, weeklyStreak(today, dates, weeklyGoal));
  }

  static int weeklyStreak(LocalDate today, Set<LocalDate> dates, int goal) {
    var counts = new HashMap<LocalDate, Integer>();
    for (LocalDate date : dates) {
      if (date.isAfter(today)) continue;
      LocalDate monday =
          date.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      counts.merge(monday, 1, Integer::sum);
    }
    LocalDate cursor =
        today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    if (counts.getOrDefault(cursor, 0) < goal) cursor = cursor.minusWeeks(1);
    int streak = 0;
    while (counts.getOrDefault(cursor, 0) >= goal) {
      streak++;
      cursor = cursor.minusWeeks(1);
    }
    return streak;
  }

  public Result get(UUID userId, LocalDate weekStart) {
    if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY)
      throw new BusinessException(WorkoutErrorCode.INVALID_WORKOUT, "weekStart는 월요일이어야 합니다.");
    Instant from = weekStart.atStartOfDay(SEOUL).toInstant();
    Instant until = weekStart.plusDays(7).atStartOfDay(SEOUL).toInstant();
    List<Session> sessions =
        jdbc.query(
            """
            SELECT s.id, s.ended_at, s.routine_snapshot
            FROM workout.workout_session s
            WHERE s.user_id = ? AND s.status = 'COMPLETED'
                AND s.ended_at >= ? AND s.ended_at < ?
            """,
            (rs, row) ->
                new Session(
                    (UUID) rs.getObject("id"),
                    rs.getTimestamp("ended_at").toInstant().atZone(SEOUL).toLocalDate(),
                    rs.getString("routine_snapshot")),
            userId,
            java.sql.Timestamp.from(from),
            java.sql.Timestamp.from(until));
    SortedSet<LocalDate> dates = new TreeSet<>();
    for (Session session : sessions) {
      JsonNode snapshot = json.readTree(session.snapshot());
      Set<String> performedSetIds =
          new HashSet<>(
              jdbc.query(
                  """
                  SELECT routine_set_id FROM workout.workout_set_result
                  WHERE session_id = ? AND (actual_reps > 0 OR actual_duration_seconds > 0)
                  """,
                  (rs, row) -> rs.getObject("routine_set_id").toString(),
                  session.id()));
      if (WorkoutCompletionPolicy.qualifies(snapshot, performedSetIds)) dates.add(session.date());
    }
    return new Result(weekStart, List.copyOf(dates));
  }

  private record Session(UUID id, LocalDate date, String snapshot) {}
}
