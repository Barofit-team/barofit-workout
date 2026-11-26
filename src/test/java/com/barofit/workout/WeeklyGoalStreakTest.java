package com.barofit.workout;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class WeeklyGoalStreakTest {
  private final LocalDate today = LocalDate.parse("2026-11-25");

  @Test
  void preservesLastCompletedWeekUntilCurrentWeekEnds() {
    var dates =
        Set.of(
            LocalDate.parse("2026-11-16"),
            LocalDate.parse("2026-11-17"),
            LocalDate.parse("2026-11-18"));
    assertThat(WeeklyWorkoutProgressService.weeklyStreak(today, dates, 3)).isEqualTo(1);
    assertThat(WeeklyWorkoutProgressService.weeklyStreak(LocalDate.parse("2026-11-30"), dates, 3))
        .isZero();
  }

  @Test
  void includesCurrentWeekOnlyOnceGoalIsMetAndStopsAtGap() {
    var dates = new HashSet<LocalDate>();
    for (String d :
        List.of(
            "2026-11-23",
            "2026-11-24",
            "2026-11-25",
            "2026-11-16",
            "2026-11-17",
            "2026-11-18",
            "2026-11-02",
            "2026-11-03",
            "2026-11-04")) dates.add(LocalDate.parse(d));
    assertThat(WeeklyWorkoutProgressService.weeklyStreak(today, dates, 3)).isEqualTo(2);
    assertThat(WeeklyWorkoutProgressService.weeklyStreak(today, dates, 4)).isZero();
  }

  @Test
  void doesNotCountFutureDatesOrPartialWeeks() {
    assertThat(
            WeeklyWorkoutProgressService.weeklyStreak(
                today, Set.of(today.plusDays(1), today.plusDays(2), today.plusDays(3)), 3))
        .isZero();
    assertThat(WeeklyWorkoutProgressService.weeklyStreak(today, Set.of(today), 2)).isZero();
  }
}
