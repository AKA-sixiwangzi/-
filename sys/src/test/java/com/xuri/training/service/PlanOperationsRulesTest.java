package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.common.Problem;
import com.xuri.training.config.Jobs;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.web.*;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class PlanOperationsRulesTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-11-17T08:00:00Z"), ZoneOffset.UTC);
  final LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
  final PlanOperationsMapper db = mock(PlanOperationsMapper.class);
  final IdentityMapper users = mock(IdentityMapper.class);
  final PlanOperationsService service = new PlanOperationsService(db, users, clock);
  final Map<String, Object> plan = row("id", 2L, "title", "岗位培训", "owner_id", 1L,
      "responsible_user_id", 9L, "status", "PUBLISHED", "start_at", now.minusDays(1),
      "due_at", now.plusHours(48), "remind_before_hours", 72, "remind_every_hours", 24,
      "push_results", true);
  final Set<String> claims = new HashSet<>();

  @BeforeEach
  void setup() {
    Current.set(new Actor(9, Set.of("EMPLOYEE")));
    when(db.plan(2)).thenReturn(plan);
    when(db.lockPlan(2)).thenReturn(plan);
    when(users.profile(9)).thenReturn(row("id", 9L, "active", true));
    when(db.pendingLearners(2)).thenReturn(List.of(row("id", 7L, "display_name", "员工甲")));
    when(db.courseStates(2)).thenReturn(List.of(row("user_id", 7L, "course_id", 3L, "completed", 0)));
    when(db.examStates(2)).thenReturn(List.of(row("user_id", 7L, "exam_id", 4L,
        "status", "NOT_STARTED", "score", null, "passed", false)));
    when(db.claimNotice(anyLong(), anyLong(), anyString(), anyString())).thenAnswer(call ->
        claims.add(Arrays.toString(call.getArguments())) ? 1 : 0);
  }

  @AfterEach
  void clear() { Current.clear(); }

  @Test
  void appointedEmployeeCanCoordinateWithoutInstructorRole() {
    when(db.visibleCourses(2, true, 9)).thenReturn(List.of(row("id", 3L, "owner_id", 8L)));
    Map<String, Object> result = service.teachingPlan(2);
    assertEquals(true, result.get("can_manage_reminders"));
    verify(db).visibleCourses(2, true, 9);
  }

  @Test
  void unrelatedEmployeeCannotReadTheRosterOrResults() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    assertEquals(403, assertThrows(Problem.class, () -> service.teachingPlan(2)).status);
    verify(db, never()).members(anyLong());
    verify(db, never()).learners(anyLong(), anyLong());
    verify(db, never()).materials(anyLong());
  }

  @Test
  void instructorReceivesOnlyOwnCourseMaterialsAndLearnerResults() {
    Current.set(new Actor(8, Set.of("EMPLOYEE", "INSTRUCTOR")));
    when(db.visibleCourses(2, false, 8)).thenReturn(List.of(row("id", 3L, "owner_id", 8L)));
    when(db.learners(2, 3)).thenReturn(List.of(row("id", 7L, "completed", false)));
    when(db.learnerExams(2, 3, 7)).thenReturn(List.of(row("exam_id", 4L, "score", 90)));
    Map<String, Object> result = service.teachingPlan(2);
    assertEquals(false, result.get("can_manage_reminders"));
    assertEquals(1, ((List<?>) result.get("courses")).size());
    verify(db, never()).materials(5);
    verify(db, never()).learners(2, 5);
    verify(db).offlineSessions(2, false, 8);
  }

  @Test
  void revokedInstructorRoleDoesNotRetainCourseDashboardAccess() {
    Current.set(new Actor(8, Set.of("EMPLOYEE")));
    assertEquals(403, assertThrows(Problem.class, () -> service.teachingPlan(2)).status);
    verify(db, never()).visibleCourses(anyLong(), anyBoolean(), anyLong());
  }

  @Test
  void instructorCannotSeeDraftTrainingPlans() {
    Current.set(new Actor(8, Set.of("INSTRUCTOR")));
    plan.put("status", "DRAFT");
    when(db.visibleCourses(2, false, 8)).thenReturn(List.of(row("id", 3L, "owner_id", 8L)));
    assertEquals(403, assertThrows(Problem.class, () -> service.teachingPlan(2)).status);
    verify(db, never()).members(anyLong());
  }

  @Test
  void administratorSeesAllLinkedCourses() {
    Current.set(new Actor(1, Set.of("ADMIN")));
    when(db.visibleCourses(2, true, 1)).thenReturn(List.of(row("id", 3L), row("id", 5L)));
    assertEquals(2, ((List<?>) service.teachingPlan(2).get("courses")).size());
    verify(db).materials(3);
    verify(db).materials(5);
  }

  @Test
  void responsibleCanSeePlanExamWithoutCoursePrerequisites() {
    when(db.visiblePlanExams(2, true, 9)).thenReturn(List.of(row("id", 6L, "title", "计划总结考试")));
    when(db.examStates(2)).thenReturn(List.of(row("user_id", 7L, "exam_id", 6L,
        "status", "PENDING_GRADE", "score", null)));
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) service.teachingPlan(2).get("plan_exams");
    assertEquals(1, rows.size());
    assertEquals(List.of(row("user_id", 7L, "exam_id", 6L, "status", "PENDING_GRADE", "score", null)),
        rows.get(0).get("results"));
  }

  @Test
  void responsibleOverviewUsesFullPlanAndDoesNotDoubleCountSharedExamGrading() {
    when(db.accessiblePlans(false, false, 9)).thenReturn(List.of(plan));
    when(db.visibleCourses(2, true, 9)).thenReturn(List.of(row("id", 3L), row("id", 5L)));
    when(db.members(2)).thenReturn(List.of(row("id", 7L)));
    for (long course : List.of(3L, 5L)) {
      when(db.learners(2, course)).thenReturn(List.of(row("id", 7L, "completed", true)));
      when(db.learnerExams(2, course, 7)).thenReturn(List.of(row("exam_id", 4L, "status", "PENDING_GRADE")));
    }
    Map<String, Object> result = service.teachingPlans().get(0);
    assertEquals(2, result.get("assigned_tasks"));
    assertEquals(2, result.get("completed_tasks"));
    assertEquals(1, result.get("pending_grade"));
    verify(db).accessiblePlans(false, false, 9);
  }

  @Test
  void responsibleCanSetReminderRulesButCannotTransferResponsibility() {
    service.rules(2, null, 48, 12, false);
    verify(db).rules(argThat(value -> value.get("responsible_user_id").equals(9L)
        && value.get("remind_before_hours").equals(48) && value.get("push_results").equals(false)));
    assertEquals(403, assertThrows(Problem.class, () -> service.rules(2, 8L, 48, 12, true)).status);
  }

  @Test
  void administratorMayAppointAnotherActiveResponsibleEmployee() {
    Current.set(new Actor(1, Set.of("ADMIN")));
    when(users.profile(8)).thenReturn(row("id", 8L, "active", true));
    service.rules(2, 8L, 72, 24, true);
    verify(users).notifyUser(8, "已指定为培训计划负责人", "岗位培训");
  }

  @Test
  void instructorCannotChangeRulesOrSendRemindersForAnotherResponsiblePerson() {
    Current.set(new Actor(8, Set.of("INSTRUCTOR")));
    assertEquals(403, assertThrows(Problem.class, () -> service.rules(2, null, 72, 24, true)).status);
    assertEquals(403, assertThrows(Problem.class, () -> service.remind(2)).status);
    verify(db, never()).claimNotice(anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void invalidReminderIntervalsAndInactiveResponsibleFailBeforeSaving() {
    assertThrows(Problem.class, () -> service.rules(2, null, -1, 24, true));
    assertThrows(Problem.class, () -> service.rules(2, null, 72, 0, true));
    assertThrows(Problem.class, () -> service.rules(2, null, 8761, 24, true));
    when(users.profile(9)).thenReturn(row("id", 9L, "active", false));
    assertThrows(Problem.class, () -> service.rules(2, null, 72, 24, true));
    verify(db, never()).rules(anyMap());
  }

  @Test
  void repeatedManualRemindersInSamePeriodDoNotSendDuplicateMessages() {
    assertEquals(3, service.remind(2).get("sent"));
    assertEquals(0, service.remind(2).get("sent"));
    verify(users, times(3)).notifyUser(anyLong(), anyString(), anyString());
  }

  @Test
  void sameResultsDoNotRepeatNextPeriodButPendingTaskRemindersDo() {
    service.remind(2);
    PlanOperationsService nextPeriod = new PlanOperationsService(db, users, Clock.offset(clock, Duration.ofHours(24)));
    assertEquals(2, nextPeriod.remind(2).get("sent"));
    verify(users, times(1)).notifyUser(eq(9L), startsWith("培训学习与考核结果更新"), anyString());
  }

  @Test
  void scoreChangesAndFinalGradingTriggerNewResponsibleDigest() {
    when(db.examStates(2)).thenReturn(List.of(row("user_id", 7L, "exam_id", 4L,
        "status", "PENDING_GRADE", "score", null, "passed", false)));
    service.remind(2);
    verify(users).notifyUser(eq(9L), startsWith("培训学习与考核结果更新"), contains("尚未最终出分"));
    when(db.examStates(2)).thenReturn(List.of(row("user_id", 7L, "exam_id", 4L,
        "status", "GRADED", "score", 89, "passed", true)));
    assertEquals(1, service.remind(2).get("sent"));
    verify(users).notifyUser(eq(9L), startsWith("培训学习与考核结果更新"), contains("最终成绩 89，已通过"));
  }

  @Test
  void assignmentGradingAndCourseCompletionAlsoTriggerDigest() {
    service.remind(2);
    when(db.assignmentStates(2)).thenReturn(List.of(row("user_id", 7L, "assignment_id", 5L,
        "status", "GRADED", "score", 95, "feedback", "已达标")));
    assertEquals(1, service.remind(2).get("sent"));
    when(db.courseStates(2)).thenReturn(List.of(row("user_id", 7L, "course_id", 3L, "completed", 1)));
    assertEquals(1, service.remind(2).get("sent"));
  }

  @Test
  void disablingResultsPushOnlyKeepsDeadlineMessages() {
    plan.put("push_results", false);
    assertEquals(2, service.remind(2).get("sent"));
    verify(db, never()).claimNotice(anyLong(), anyLong(), eq("RESULT_DIGEST"), anyString());
  }

  @Test
  void noDeadlineMessageBeforeWindowOrBeforePlanStart() {
    plan.put("push_results", false);
    plan.put("due_at", now.plusHours(96));
    assertEquals(0, service.remind(2).get("sent"));
    plan.put("due_at", now.plusHours(4));
    plan.put("start_at", now.plusHours(1));
    assertEquals(0, service.remind(2).get("sent"));
    verify(users, never()).notifyUser(anyLong(), anyString(), anyString());
  }

  @Test
  void overdueNoticesAreClearlyLabeledAndStillDeduplicated() {
    plan.put("due_at", now.minusHours(1));
    service.remind(2);
    service.remind(2);
    verify(users, times(1)).notifyUser(eq(7L), startsWith("培训已逾期提醒"), anyString());
    verify(users, times(1)).notifyUser(eq(9L), startsWith("培训已逾期提醒"), anyString());
  }

  @Test
  void submittedWorkAwaitingGradingOnlyRemindsResponsiblePerson() {
    when(db.pendingLearners(2)).thenReturn(List.of());
    when(db.examStates(2)).thenReturn(List.of(row("user_id", 7L, "exam_id", 4L,
        "status", "PENDING_GRADE", "score", null, "passed", false)));
    assertEquals(2, service.remind(2).get("sent"));
    verify(users, never()).notifyUser(eq(7L), anyString(), anyString());
    verify(users).notifyUser(eq(9L), startsWith("培训到期提醒"), contains("1 项考核待评阅"));
  }

  @Test
  void disabledResponsibleDoesNotReceiveResultsButLearnersStillDo() {
    when(users.profile(9)).thenReturn(row("id", 9L, "active", false));
    assertEquals(1, service.remind(2).get("sent"));
    verify(users, never()).notifyUser(eq(9L), anyString(), anyString());
  }

  @Test
  void scheduledDispatchWorksWithoutThreadLocalLoginAndSkipsDrafts() {
    Current.clear();
    assertEquals(3, service.scheduledDispatch(2).get("sent"));
    plan.put("status", "DRAFT");
    assertEquals(0, service.scheduledDispatch(2).get("sent"));
  }

  @Test
  void manualDispatchRejectsDrafts() {
    plan.put("status", "DRAFT");
    assertEquals(409, assertThrows(Problem.class, () -> service.remind(2)).status);
    verify(db, never()).claimNotice(anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void jobFailureForOnePlanDoesNotBlockOtherPlans() {
    PlanOperationsService jobsService = mock(PlanOperationsService.class);
    when(jobsService.publishedPlans()).thenReturn(List.of(2L, 3L));
    when(jobsService.scheduledDispatch(2)).thenThrow(new IllegalStateException("单个计划失败"));
    Jobs jobs = new Jobs(mock(ExamMapper.class), mock(ExamService.class), users, jobsService, clock);
    jobs.maintenance();
    verify(jobsService).scheduledDispatch(3);
    verify(users).expireTokens();
    verify(users).expireTickets();
  }

  @Test
  void planCreateRequestRemainsCompatibleWithMissingOptionalReminderFields() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertTrue(validator.validate(new ProgramController.PlanCreate("计划", now.atOffset(ZoneOffset.UTC),
          now.plusDays(1).atOffset(ZoneOffset.UTC), List.of(3L), List.of(7L), List.of(), null, null, null, null)).isEmpty());
      assertFalse(validator.validate(new PlanOperationsController.ReminderRules(null, 72, 0, true)).isEmpty());
      assertFalse(validator.validate(new PlanOperationsController.ReminderRules(null, 72, 24, null)).isEmpty());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void legacyServicePlanCreationSetsResponsibleAndDefaultRules() {
    Current.set(new Actor(1, Set.of("ADMIN")));
    ProgramMapper plans = mock(ProgramMapper.class);
    CourseMapper courses = mock(CourseMapper.class);
    ProgramService programs = new ProgramService(plans, courses, users, mock(WarningMapper.class), mock(ExamMapper.class));
    when(users.profile(1)).thenReturn(row("id", 1L, "active", true));
    when(users.profile(7)).thenReturn(row("id", 7L, "active", true));
    when(courses.course(3)).thenReturn(row("id", 3L, "status", "PUBLISHED"));
    doAnswer(call -> { ((Map<String, Object>) call.getArgument(0)).put("id", 2L); return null; }).when(plans).plan(anyMap());
    Map<String, Object> result = programs.plan("计划", now, now.plusDays(1), List.of(3L), List.of(7L), List.of());
    assertEquals(1L, result.get("responsible_user_id"));
    assertEquals(72, result.get("remind_before_hours"));
    assertEquals(24, result.get("remind_every_hours"));
    assertEquals(true, result.get("push_results"));
  }
}
