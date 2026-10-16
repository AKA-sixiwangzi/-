package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class LearningServiceTest {
  LearningMapper db = mock(LearningMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  CourseMapper courses = mock(CourseMapper.class);
  CourseService access = mock(CourseService.class);
  WarningService warnings = mock(WarningService.class);
  WarningMapper warningDb = mock(WarningMapper.class);
  Clock clock = Clock.fixed(Instant.ofEpochMilli(20000), ZoneOffset.UTC);
  LearningService service =
      new LearningService(db, users, courses, access, warnings, warningDb, clock);

  @BeforeEach
  void set() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    when(db.event(anyString(), anyInt())).thenReturn(null);
    when(courses.lesson(3))
        .thenReturn(
            row(
                "id",
                3L,
                "course_id",
                4L,
                "duration_seconds",
                100,
                "coverage_threshold",
                .95,
                "time_threshold",
                .95));
    when(db.progressList(7, 4)).thenReturn(List.of(row("completed", 0)));
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  Map<String, Object> session() {
    return row(
        "id",
        "session",
        "user_id",
        7L,
        "lesson_id",
        3L,
        "active",
        true,
        "last_at_ms",
        10000L,
        "last_seq",
        0,
        "last_position",
        0d,
        "playing",
        true);
  }

  @Test
  void simultaneousStartWarnsAndDoesNotCreateAnotherSession() {
    when(db.active(7)).thenReturn(session());
    var r = service.start(3);
    assertEquals(true, r.get("conflict"));
    verify(warnings).raise(7, 4, "session", "MULTI_PLAY", "已有有效播放会话；尝试启动章节：3");
    verify(db, never()).start(anyMap());
  }

  @Test
  void ordinarySeekIsLoggedWithoutAdminWarning() {
    when(db.session("session")).thenReturn(session());
    service.heartbeat("session", 1, 80, "SEEK", true, 1);
    verify(db)
        .eventCreate(
            argThat(
                r ->
                    r.get("type").equals("FORWARD_SEEK")
                        && ((Number) r.get("accepted")).doubleValue() == 0));
    verify(db, never()).segment(anyMap());
    verifyNoInteractions(warnings);
  }

  @Test
  void frequentSeekRaisesWarning() {
    when(db.session("session")).thenReturn(session());
    when(db.recentSeeks("session")).thenReturn(3);
    service.heartbeat("session", 1, 80, "SEEK", true, 1);
    verify(warnings).raise(eq(7L), eq(4L), eq("session"), eq("FREQUENT_SEEK"), anyString());
  }

  @Test
  void duplicateEventNeverEarnsTimeTwice() {
    when(db.session("session")).thenReturn(session());
    when(db.event("session", 1)).thenReturn(row("seq", 1, "accepted_seconds", 10));
    assertEquals(true, service.heartbeat("session", 1, 10, "HEARTBEAT", true, 1).get("duplicate"));
    verify(db, never()).segment(anyMap());
    verify(db, never()).eventCreate(anyMap());
  }

  @Test
  void futureSequenceAndOtherUsersSessionAreRejected() {
    when(db.session("session")).thenReturn(session());
    assertThrows(Problem.class, () -> service.heartbeat("session", 3, 10, "HEARTBEAT", true, 1));
    Current.set(new Actor(8, Set.of("EMPLOYEE")));
    assertEquals(
        404,
        assertThrows(Problem.class, () -> service.heartbeat("session", 1, 10, "HEARTBEAT", true, 1))
            .status);
  }

  @Test
  void validHeartbeatStoresRealInterval() {
    when(db.session("session")).thenReturn(session());
    var r = service.heartbeat("session", 1, 10, "HEARTBEAT", true, 1);
    assertEquals(10d, r.get("acceptedSeconds"));
    verify(db).segment(argThat(s -> s.get("start").equals(0d) && s.get("end").equals(10d)));
  }

  @Test
  void repeatWatchingDoesNotCompleteCoverage() {
    when(db.segments(7, 3))
        .thenReturn(
            List.of(
                row("start_seconds", 0d, "end_seconds", 10d),
                row("start_seconds", 0d, "end_seconds", 10d)));
    service.recalculate(7, 3);
    verify(db)
        .progress(
            argThat(
                r ->
                    r.get("seconds").equals(20d)
                        && r.get("covered").equals(10d)
                        && r.get("done").equals(false)));
  }
}
