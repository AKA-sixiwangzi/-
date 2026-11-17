package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.common.Problem;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.web.OfflineController;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;

class OfflineRulesTest {
  OfflineMapper db = mock(OfflineMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  CourseMapper courses = mock(CourseMapper.class);
  Clock clock = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
  OfflineService service = new OfflineService(db, users, courses, clock);
  OffsetDateTime start = OffsetDateTime.parse("2026-11-17T09:00:00Z");
  OffsetDateTime end = start.plusHours(1);

  Map<String, Object> session() {
    return row("id", 10L, "title", "实操培训", "instructor_id", 8L,
        "start_at", start.toLocalDateTime(), "end_at", end.toLocalDateTime());
  }

  OfflineService.Attendance attendance(long user, String status) {
    return new OfflineService.Attendance(user, status, 90, "实操完成");
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    Current.set(new Actor(8, Set.of("EMPLOYEE", "INSTRUCTOR")));
    when(db.session(10)).thenReturn(session());
    when(db.lockSession(10)).thenReturn(session());
    when(users.lockUser(7)).thenReturn(row("id", 7L, "active", true));
    when(users.lockUser(8)).thenReturn(row("id", 8L, "active", true));
    when(users.lockUser(9)).thenReturn(row("id", 9L, "active", true));
    when(users.roles(8)).thenReturn(Set.of("EMPLOYEE", "INSTRUCTOR"));
    when(courses.lockCourse(3)).thenReturn(row("id", 3L, "owner_id", 8L, "status", "PUBLISHED"));
    when(courses.enrolled(7, 3)).thenReturn(1);
    when(db.ownedLearner(8, 7)).thenReturn(1);
    when(db.attendanceRow(10, 7)).thenReturn(row("user_id", 7L, "status", "REGISTERED", "note", null));
    when(db.record(anyMap())).thenReturn(1);
    doAnswer(invocation -> {
      ((Map<String, Object>) invocation.getArgument(0)).put("id", 10L);
      return null;
    }).when(db).create(anyMap());
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void employeeCannotEnumerateManagementOptionsOrCreateSession() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    assertEquals(403, assertThrows(Problem.class, service::options).status);
    assertEquals(403, assertThrows(Problem.class, () -> service.create(
        "培训", null, null, 8, start, end, "会议室", List.of(7L))).status);
    verify(db, never()).create(anyMap());
  }

  @Test
  void employeeDetailContainsOnlyOwnAttendance() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    when(db.attendance(10, false, 7)).thenReturn(List.of(row("user_id", 7L, "status", "PRESENT")));
    Map<String, Object> result = service.detail(10);
    assertEquals(false, result.get("can_manage"));
    var records = (List<?>) result.get("attendance");
    assertEquals(1, records.size());
    verify(db).attendance(10, false, 7);
    verify(db, never()).attendance(eq(10L), eq(true), anyLong());
  }

  @Test
  void unrelatedInstructorCannotReadRosterOrRecordAttendance() {
    Current.set(new Actor(9, Set.of("EMPLOYEE", "INSTRUCTOR")));
    when(db.attendanceRow(10, 9)).thenReturn(null);
    assertEquals(403, assertThrows(Problem.class, () -> service.detail(10)).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> service.record(10, List.of(attendance(7, "PRESENT")), "课后登记")).status);
    verify(db, never()).record(anyMap());
  }

  @Test
  void assignedInstructorCanReadFullRoster() {
    when(db.attendance(10, true, 8)).thenReturn(List.of(row("user_id", 7L, "status", "PRESENT")));
    assertEquals(true, service.detail(10).get("can_manage"));
    verify(db).attendance(10, true, 8);
  }

  @Test
  void instructorCreatesOnlySelfSessionForEnrolledLearnersAndDoesNotCompleteVideo() {
    var result = service.create("实操培训", null, 3L, 8, start, end, "会议室", List.of(7L, 7L));
    assertEquals(8L, result.get("instructor_id"));
    verify(db, times(1)).register(10, 7);
    verify(users).notifyUser(eq(7L), contains("线下培训安排"), contains("会议室"));
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(courses, never()).completion(anyLong(), anyLong(), anyBoolean());
  }

  @Test
  void instructorCannotImpersonateAnotherInstructorOrUseForeignCourse() {
    assertEquals(403, assertThrows(Problem.class, () -> service.create(
        "培训", null, 3L, 9, start, end, "会议室", List.of(7L))).status);
    when(courses.lockCourse(3)).thenReturn(row("id", 3L, "owner_id", 9L, "status", "PUBLISHED"));
    assertEquals(403, assertThrows(Problem.class, () -> service.create(
        "培训", null, 3L, 8, start, end, "会议室", List.of(7L))).status);
    verify(db, never()).create(anyMap());
  }

  @Test
  void allTargetLearnersValidatedBeforeCreatingAnything() {
    assertEquals(403, assertThrows(Problem.class, () -> service.create(
        "培训", null, 3L, 8, start, end, "会议室", List.of(7L, 9L))).status);
    verify(db, never()).create(anyMap());
    verify(db, never()).register(anyLong(), anyLong());
    verify(users, never()).notifyUser(anyLong(), anyString(), anyString());
  }

  @Test
  void unlinkedInstructorSessionStillRequiresOwnLearners() {
    assertThrows(Problem.class, () -> service.create(
        "培训", null, null, 8, start, end, "会议室", List.of(9L)));
    verify(db, never()).create(anyMap());
    service.create("培训", null, null, 8, start, end, "会议室", List.of(7L));
    verify(db).create(anyMap());
  }

  @Test
  void invalidDurationAndUnpublishedCoursesRejected() {
    assertThrows(Problem.class, () -> service.create(
        "培训", null, null, 8, start, start, "会议室", List.of(7L)));
    assertThrows(Problem.class, () -> service.create(
        "培训", null, null, 8, start, start.plusHours(25), "会议室", List.of(7L)));
    when(courses.lockCourse(3)).thenReturn(row("id", 3L, "owner_id", 8L, "status", "ARCHIVED"));
    assertEquals(409, assertThrows(Problem.class, () -> service.create(
        "培训", null, 3L, 8, start, end, "会议室", List.of(7L))).status);
    verify(db, never()).create(anyMap());
  }

  @Test
  void linkedPlanRequiresItsCourseAndAllPlanMembers() {
    when(db.plan(2)).thenReturn(row("id", 2L, "status", "PUBLISHED"));
    assertThrows(Problem.class, () -> service.create(
        "培训", 2L, 3L, 8, start, end, "会议室", List.of(7L)));
    when(db.planCourse(2, 3)).thenReturn(1);
    assertThrows(Problem.class, () -> service.create(
        "培训", 2L, 3L, 8, start, end, "会议室", List.of(7L)));
    verify(db, never()).create(anyMap());
    when(db.planMember(2, 7)).thenReturn(1);
    service.create("培训", 2L, 3L, 8, start, end, "会议室", List.of(7L));
    verify(db).create(anyMap());
  }

  @Test
  void forgedPlanWithoutCourseCannotBypassPlanOptionsAuthorization() {
    when(db.plan(2)).thenReturn(row("id", 2L, "status", "PUBLISHED", "owner_id", 1L));
    when(db.planMember(2, 7)).thenReturn(1);
    assertEquals(403, assertThrows(Problem.class, () -> service.create(
        "培训", 2L, null, 8, start, end, "会议室", List.of(7L))).status);
    verify(db, never()).create(anyMap());
    verify(db, never()).register(anyLong(), anyLong());
    verify(users, never()).notifyUser(anyLong(), anyString(), anyString());
  }

  @Test
  void authorizedPlanWithoutCourseStillAcceptsOwnEnrolledLearners() {
    when(db.plan(2)).thenReturn(row("id", 2L, "status", "PUBLISHED", "responsible_user_id", 8L));
    when(db.authorizedPlan(2, 8)).thenReturn(1);
    when(db.planMember(2, 7)).thenReturn(1);
    service.create("培训", 2L, null, 8, start, end, "会议室", List.of(7L));
    verify(db).create(anyMap());
  }

  @Test
  void attendanceCannotBeRecordedBeforeSessionStarts() {
    Map<String, Object> future = session();
    future.put("start_at", end.plusHours(1).toLocalDateTime());
    future.put("end_at", end.plusHours(2).toLocalDateTime());
    when(db.lockSession(10)).thenReturn(future);
    assertEquals(409, assertThrows(Problem.class,
        () -> service.record(10, List.of(attendance(7, "PRESENT")), "提前登记")).status);
    verify(db, never()).record(anyMap());
  }

  @Test
  void mixedBatchUnknownMemberDoesNotPartiallyRecordFirstMember() {
    when(db.attendanceRow(10, 9)).thenReturn(null);
    assertThrows(Problem.class, () -> service.record(10,
        List.of(attendance(7, "PRESENT"), attendance(9, "PRESENT")), "课后登记"));
    verify(db, never()).record(anyMap());
    verify(users, never()).notifyUser(anyLong(), anyString(), anyString());
  }

  @Test
  void inactiveEmployeeAndDuplicateRecordsAreRejectedBeforeWrites() {
    when(users.lockUser(7)).thenReturn(row("id", 7L, "active", false));
    assertThrows(Problem.class,
        () -> service.record(10, List.of(attendance(7, "PRESENT")), "课后登记"));
    assertEquals(400, assertThrows(Problem.class, () -> service.record(10,
        List.of(attendance(7, "PRESENT"), attendance(7, "ABSENT")), "课后登记")).status);
    verify(db, never()).record(anyMap());
  }

  @Test
  void attendanceRecordsScoresAuditAndNotifiesWithoutOnlineProgressMutation() {
    service.record(10, List.of(attendance(7, "PRESENT")), "课后登记");
    verify(db).record(argThat(value -> value.get("user_id").equals(7L)
        && value.get("status").equals("PRESENT") && value.get("score").equals(90)));
    verify(users).audit(eq(8L), eq("OFFLINE_ATTENDANCE"), eq("session=10;user=7"), contains("课后登记"));
    verify(users).audit(8L, "OFFLINE_NOTE_AFTER", "session=10;user=7", "实操完成");
    verify(users).notifyUser(eq(7L), contains("出勤记录"), contains("90"));
    verifyNoInteractions(courses);
  }

  @Test
  void personalHoursCreditOnlyPresentAndFinishedSessions() {
    Map<String, Object> present = session(); present.put("attendance_status", "PRESENT");
    Map<String, Object> absent = session(); absent.put("attendance_status", "ABSENT");
    Map<String, Object> ongoing = session(); ongoing.put("attendance_status", "PRESENT");
    ongoing.put("end_at", end.plusHours(1).toLocalDateTime());
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    when(db.list(false, false, 7)).thenReturn(List.of(present, absent, ongoing));
    var result = service.personal();
    assertEquals(1.0, result.get("credited_hours"));
    assertEquals(1L, result.get("attended_sessions"));
    verify(db).list(false, false, 7);
  }

  @Test
  void httpRecordsRejectOutOfRangeScoresAndRegisteredStatus() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertFalse(validator.validate(new OfflineController.Record(7, "PRESENT", 101, "")).isEmpty());
      assertFalse(validator.validate(new OfflineController.Record(7, "REGISTERED", null, "")).isEmpty());
      assertFalse(validator.validate(new OfflineController.Attendance(
          Arrays.asList((OfflineController.Record) null), "原因")).isEmpty());
      assertTrue(validator.validate(new OfflineController.Record(7, "EXCUSED", null, "请假")).isEmpty());
    }
  }

  @Test
  void allOfflineSqlStatementsParse() {
    Configuration configuration = new Configuration();
    configuration.addMapper(OfflineMapper.class);
    assertTrue(configuration.hasStatement(OfflineMapper.class.getName() + ".eligibleEnrollments"));
    assertTrue(configuration.hasStatement(OfflineMapper.class.getName() + ".record"));
  }
}
