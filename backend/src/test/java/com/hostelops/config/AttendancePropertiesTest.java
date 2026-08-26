package com.hostelops.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The absence-alert calendar.
 *
 * <p>What makes this worth a test class of its own is that it decides what
 * "10 consecutive absent days" counts. Over calendar days, every student trips the
 * threshold after a fortnight's holiday, the wardens get a wall of alerts they know
 * are wrong, and the feature is muted within a week -- so the alert that matters
 * arrives into a channel nobody reads any more. Counting only working days is the
 * difference between an alerting feature and an ignored one.
 */
class AttendancePropertiesTest {

    private static final Set<DayOfWeek> WEEKEND = Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    /** A Monday, so weekday arithmetic below is easy to follow. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 17);

    private static AttendanceProperties properties(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {
        return new AttendanceProperties(10, weekend, holidays);
    }

    @Nested
    @DisplayName("the threshold")
    class Threshold {

        @Test
        @DisplayName("is accepted at its smallest useful value")
        void oneIsValid() {
            assertThat(properties(WEEKEND, Set.of()).absenceAlertThreshold()).isEqualTo(10);
            assertThat(new AttendanceProperties(1, WEEKEND, Set.of()).absenceAlertThreshold()).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -10})
        @DisplayName("is rejected at zero or below, at construction time")
        void nonPositiveIsRejected(int threshold) {
            // A threshold of 0 would raise an alert for every student on every scan,
            // which is indistinguishable from the alerting being broken. Failing here
            // means a bad value stops the application at boot, where someone is
            // watching, instead of at 6am when the scan runs.
            assertThatThrownBy(() -> new AttendanceProperties(threshold, WEEKEND, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.attendance.absence-alert-threshold")
                    .hasMessageContaining(String.valueOf(threshold));
        }
    }

    @Nested
    @DisplayName("working days")
    class WorkingDays {

        @ParameterizedTest
        @EnumSource(value = DayOfWeek.class,
                names = {"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"})
        @DisplayName("a weekday with no holiday counts")
        void weekdaysCount(DayOfWeek day) {
            LocalDate date = MONDAY.with(day);

            assertThat(properties(WEEKEND, Set.of()).isWorkingDay(date)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = DayOfWeek.class, names = {"SATURDAY", "SUNDAY"})
        @DisplayName("a weekend day does not count")
        void weekendsDoNotCount(DayOfWeek day) {
            LocalDate date = MONDAY.with(day);

            assertThat(properties(WEEKEND, Set.of()).isWorkingDay(date)).isFalse();
        }

        @Test
        @DisplayName("a declared holiday does not count even though it is a weekday")
        void holidaysDoNotCount() {
            LocalDate independenceDay = LocalDate.of(2026, 8, 15);
            AttendanceProperties props = properties(Set.of(), Set.of(independenceDay));

            assertThat(props.isWorkingDay(independenceDay)).isFalse();
            assertThat(props.isWorkingDay(independenceDay.plusDays(1))).isTrue();
        }

        @Test
        @DisplayName("a six-day week is expressible, because the calendar is configuration")
        void weekendCanBeASingleDay() {
            AttendanceProperties props = properties(Set.of(DayOfWeek.SUNDAY), Set.of());

            // The hostel that works Saturdays changes one line of YAML. Hardcoding
            // Sat+Sun would have made this a code change and a redeploy.
            assertThat(props.isWorkingDay(MONDAY.with(DayOfWeek.SATURDAY))).isTrue();
            assertThat(props.isWorkingDay(MONDAY.with(DayOfWeek.SUNDAY))).isFalse();
        }

        @Test
        @DisplayName("with nothing configured, every day counts")
        void emptyCalendarMeansEveryDayCounts() {
            AttendanceProperties props = properties(Set.of(), Set.of());

            assertThat(props.isWorkingDay(MONDAY.with(DayOfWeek.SATURDAY))).isTrue();
            assertThat(props.isWorkingDay(MONDAY.with(DayOfWeek.SUNDAY))).isTrue();
        }
    }

    @Nested
    @DisplayName("binding")
    class Binding {

        @Test
        @DisplayName("absent configuration binds as empty rather than blowing up on null")
        void nullSetsBecomeEmpty() {
            // Spring binds a commented-out or blank key as null. Without the compact
            // constructor's defaulting, the first isWorkingDay call would be an NPE
            // inside a scheduled job -- the least observable place for one.
            AttendanceProperties props = new AttendanceProperties(10, null, null);

            assertThat(props.weekendDays()).isEmpty();
            assertThat(props.holidays()).isEmpty();
            assertThat(props.isWorkingDay(MONDAY)).isTrue();
        }

        @Test
        @DisplayName("the calendar cannot be changed underneath a running scan")
        void setsAreCopiedDefensively() {
            Set<DayOfWeek> mutableWeekend = new HashSet<>(Set.of(DayOfWeek.SUNDAY));
            Set<LocalDate> mutableHolidays = new HashSet<>();
            AttendanceProperties props = properties(mutableWeekend, mutableHolidays);

            mutableWeekend.add(DayOfWeek.SATURDAY);
            mutableHolidays.add(MONDAY);

            // Set.copyOf in the constructor. A configuration object whose contents can
            // shift is a scan that counts one thing at the start and another at the end.
            assertThat(props.isWorkingDay(MONDAY.with(DayOfWeek.SATURDAY))).isTrue();
            assertThat(props.isWorkingDay(MONDAY)).isTrue();
        }
    }
}
