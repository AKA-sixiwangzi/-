package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.common.Problem;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.web.LearningRequestController;
import jakarta.validation.Validation;
import java.util.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;

class LearningRequestRulesTest {
  LearningRequestMapper db = mock(LearningRequestMapper.class);
  CourseMapper courses = mock(CourseMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  LearningRequestService service = new LearningRequestService(db, courses, users);

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    Current.set(new Actor(9, Set.of("EMPLOYEE", "MANAGER")));
    when(db.managedDepartments(9)).thenReturn(List.of(1L));
    when(courses.lockCourse(3)).thenReturn(row("id", 3L, "status", "PUBLISHED"));
    when(courses.lockCourse(4)).thenReturn(row("id", 4L, "status", "PUBLISHED"));
    when(users.lockUser(7)).thenReturn(row("id", 7L, "department_id", 1L, "active", true));
    when(users.lockUser(8)).thenReturn(row("id", 8L, "department_id", 2L, "active", true));
    when(users.lockUser(9)).thenReturn(row("id", 9L, "department_id", 1L, "active", true));
    when(users.admins()).thenReturn(List.of(1L));
    when(db.requesterRoles(9)).thenReturn(Set.of("EMPLOYEE", "MANAGER"));
    when(db.lockRequest(11)).thenReturn(row("id", 11L, "requester_id", 9L, "status", "PENDING"));
    when(db.requestCourses(11)).thenReturn(List.of(row("id", 3L, "title", "新课")));
    when(db.requestUsers(11)).thenReturn(List.of(row("id", 7L, "display_name", "员工甲")));
    when(db.review(eq(11L), anyLong(), anyString(), anyString())).thenReturn(1);
    doAnswer(call -> {
      ((Map<String, Object>) call.getArgument(0)).put("id", 11L);
      return null;
    }).when(db).create(anyMap());
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  private void admin() {
    Current.set(new Actor(1, Set.of("EMPLOYEE", "ADMIN")));
  }

  @Test
  void ordinaryEmployeeCannotEnumerateOrSubmitOrGrantCourses() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    assertEquals(403, assertThrows(Problem.class, service::list).status);
    assertEquals(403, assertThrows(Problem.class, service::options).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> service.create(null, List.of(3L), List.of(7L), "补充培训")).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> service.directGrant(List.of(3L), List.of(7L), "补充培训")).status);
    verifyNoInteractions(db, courses, users);
  }

  @Test
  void managerReadsOnlyOwnRequestsWithNamedUsersAndCourses() {
    when(db.list(false, 9)).thenReturn(List.of(row("id", 11L, "status", "PENDING")));
    Map<String, Object> result = service.list().get(0);
    assertEquals(List.of(row("id", 3L, "title", "新课")), result.get("courses"));
    assertEquals(List.of(row("id", 7L, "display_name", "员工甲")), result.get("users"));
    verify(db).list(false, 9);
    verify(db, never()).list(eq(true), anyLong());
  }

  @Test
  void managerOptionsUseExplicitScopeAndOnlyScopedRouteMembers() {
    when(db.eligibleUsers(false, 9)).thenReturn(List.of(row("id", 7L)));
    when(db.eligiblePaths(false, 9)).thenReturn(List.of(row("id", 2L, "title", "成长路线")));
    when(db.pathUsers(2, false, 9)).thenReturn(List.of(7L));
    Map<String, Object> result = service.options();
    assertEquals(List.of(row("id", 7L)), result.get("users"));
    assertEquals(List.of(row("id", 2L, "title", "成长路线", "user_ids", List.of(7L))), result.get("paths"));
    verify(db).eligibleUsers(false, 9);
    verify(db, never()).eligibleUsers(eq(true), anyLong());
  }

  @Test
  void creatingPendingRequestDoesNotGrantCourseAccess() {
    Map<String, Object> result = service.create(null, List.of(3L, 3L), List.of(7L, 7L), "  岗位补课  ");
    assertEquals("PENDING", result.get("status"));
    assertEquals("岗位补课", result.get("reason"));
    verify(db, times(1)).course(11, 3);
    verify(db, times(1)).user(11, 7);
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(users).notifyUser(eq(1L), contains("待处理加课需求"), eq("岗位补课"));
    verify(users).audit(9L, "LEARNING_REQUEST_CREATE", "11", "岗位补课");
  }

  @Test
  void managerCannotForgeEmployeeOutsideAuthorizedDepartments() {
    assertEquals(403, assertThrows(Problem.class,
        () -> service.create(null, List.of(3L), List.of(7L, 8L), "申请课程")).status);
    verify(db, never()).create(anyMap());
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(users, never()).notifyUser(anyLong(), anyString(), anyString());
  }

  @Test
  void ownDepartmentDoesNotImplicitlyGiveManagementScope() {
    when(db.managedDepartments(9)).thenReturn(List.of());
    assertEquals(403, assertThrows(Problem.class,
        () -> service.create(null, List.of(3L), List.of(7L), "申请课程")).status);
    verify(db, never()).create(anyMap());
  }

  @Test
  void unknownOrInactiveEmployeesCannotBeRequested() {
    // MyBatis returns null for a missing row; Mockito otherwise supplies an empty map.
    when(users.lockUser(99)).thenReturn(null);
    assertEquals(404, assertThrows(Problem.class,
        () -> service.create(null, List.of(3L), List.of(99L), "申请课程")).status);
    when(users.lockUser(7)).thenReturn(row("id", 7L, "department_id", 1L, "active", false));
    assertEquals(409, assertThrows(Problem.class,
        () -> service.create(null, List.of(3L), List.of(7L), "申请课程")).status);
    verify(db, never()).create(anyMap());
  }

  @Test
  void optionalRouteRequiresPublishedRouteAndExistingMembership() {
    when(db.path(2)).thenReturn(row("id", 2L, "published", true));
    assertEquals(409, assertThrows(Problem.class,
        () -> service.create(2L, List.of(3L), List.of(7L), "路线补课")).status);
    verify(db, never()).create(anyMap());
    when(db.assigned(2, 7)).thenReturn(1);
    assertEquals(2L, service.create(2L, List.of(3L), List.of(7L), "路线补课").get("path_id"));
  }

  @Test
  void unpublishedCourseCannotBeRequested() {
    when(courses.lockCourse(3)).thenReturn(row("id", 3L, "status", "DRAFT"));
    assertEquals(409, assertThrows(Problem.class,
        () -> service.create(null, List.of(3L), List.of(7L), "申请课程")).status);
    verify(db, never()).create(anyMap());
  }

  @Test
  void onlyOrganizationDevelopmentCanReviewOrDirectlyGrant() {
    assertEquals(403, assertThrows(Problem.class, () -> service.review(11, true, "通过")).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> service.directGrant(List.of(3L), List.of(7L), "补充培训")).status);
    verify(db, never()).lockRequest(anyLong());
    verify(courses, never()).enroll(anyLong(), anyLong());
  }

  @Test
  void approvalOnlyGrantsRequestedEmployeesAndCoursesAndRecordsResult() {
    admin();
    service.review(11, true, "同意岗位补课");
    verify(courses, times(1)).enroll(7, 3);
    verify(courses, times(1)).enroll(anyLong(), anyLong());
    verify(db).review(11, 1, "APPROVED", "同意岗位补课");
    verify(users).notifyUser(eq(7L), contains("开通"), contains("同意岗位补课"));
    verify(users).notifyUser(9, "加课需求已通过 #11", "同意岗位补课");
    verify(users).audit(1L, "LEARNING_REQUEST_APPROVE", "11", "同意岗位补课");
    verify(courses, never()).completion(anyLong(), anyLong(), anyBoolean());
  }

  @Test
  void rejectedRequestNeverGrantsCoursesAndRemainsTraceable() {
    admin();
    service.review(11, false, "课程安排需调整");
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(db).review(11, 1, "REJECTED", "课程安排需调整");
    verify(users).notifyUser(9, "加课需求已驳回 #11", "课程安排需调整");
    verify(users).audit(1L, "LEARNING_REQUEST_REJECT", "11", "课程安排需调整");
    verify(courses, never()).lockCourse(anyLong());
  }

  @Test
  void previouslyHandledRequestCannotBeProcessedAgain() {
    admin();
    when(db.lockRequest(11)).thenReturn(row("id", 11L, "requester_id", 9L, "status", "APPROVED"));
    assertEquals(409, assertThrows(Problem.class, () -> service.review(11, true, "再通过")).status);
    verify(db, never()).review(anyLong(), anyLong(), anyString(), anyString());
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(users, never()).notifyUser(anyLong(), anyString(), anyString());
  }

  @Test
  void approvalRechecksRequesterCurrentRole() {
    admin();
    when(db.requesterRoles(9)).thenReturn(Set.of("EMPLOYEE"));
    assertEquals(409, assertThrows(Problem.class, () -> service.review(11, true, "同意")).status);
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(db, never()).review(anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void approvalRechecksRequesterIsStillActive() {
    admin();
    when(users.lockUser(9)).thenReturn(row("id", 9L, "active", false));
    assertEquals(409, assertThrows(Problem.class, () -> service.review(11, true, "同意")).status);
    verify(courses, never()).enroll(anyLong(), anyLong());
  }

  @Test
  void approvalRechecksScopeAfterRevocationOrEmployeeTransfer() {
    admin();
    when(db.managedDepartments(9)).thenReturn(List.of(2L));
    assertEquals(403, assertThrows(Problem.class, () -> service.review(11, true, "同意")).status);
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(users, never()).notifyUser(anyLong(), anyString(), anyString());
    verify(db, never()).review(anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void approvalRechecksArchivedCourseWithoutPartiallyGrantingValidCourse() {
    admin();
    when(db.requestCourses(11)).thenReturn(List.of(row("id", 3L), row("id", 4L)));
    when(courses.lockCourse(4)).thenReturn(row("id", 4L, "status", "ARCHIVED"));
    assertEquals(409, assertThrows(Problem.class, () -> service.review(11, true, "同意")).status);
    verify(courses, never()).enroll(anyLong(), anyLong());
  }

  @Test
  void approvalRechecksAllEmployeesBeforeAnyGrants() {
    admin();
    when(db.requestUsers(11)).thenReturn(List.of(row("id", 7L), row("id", 8L)));
    assertThrows(Problem.class, () -> service.review(11, true, "同意"));
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(users, never()).notifyUser(anyLong(), anyString(), anyString());
  }

  @Test
  void approvalRechecksOptionalRouteMembership() {
    admin();
    when(db.lockRequest(11)).thenReturn(row("id", 11L, "requester_id", 9L, "status", "PENDING", "path_id", 2L));
    when(db.path(2)).thenReturn(row("id", 2L, "published", true));
    assertEquals(409, assertThrows(Problem.class, () -> service.review(11, true, "同意")).status);
    verify(courses, never()).enroll(anyLong(), anyLong());
  }

  @Test
  void directGrantIsDeduplicatedPersonalAndAuditIdentifiesEachPersonAndCourse() {
    admin();
    assertEquals(row("user_count", 1, "course_count", 1),
        service.directGrant(List.of(3L, 3L), List.of(8L, 8L), "个人补课"));
    verify(courses, times(1)).enroll(8, 3);
    verify(courses, times(1)).enroll(anyLong(), anyLong());
    verify(users).audit(1L, "COURSE_GRANT", "user=8;course=3", "个人补课");
    verify(users, never()).notifyUser(eq(7L), anyString(), anyString());
    verify(courses, never()).completion(anyLong(), anyLong(), anyBoolean());
  }

  @Test
  void directGrantRejectsInactiveSecondEmployeeBeforeWritingAnything() {
    admin();
    when(users.lockUser(8)).thenReturn(row("id", 8L, "active", false));
    assertThrows(Problem.class,
        () -> service.directGrant(List.of(3L), List.of(7L, 8L), "个人补课"));
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(users, never()).notifyUser(anyLong(), anyString(), anyString());
    verify(users, never()).audit(anyLong(), anyString(), anyString(), anyString());
  }

  @Test
  void malformedIdsAndMissingExplanationsCannotBypassServiceValidation() {
    assertThrows(Problem.class, () -> service.create(null, null, List.of(7L), "申请"));
    assertThrows(Problem.class, () -> service.create(null, List.of(), List.of(7L), "申请"));
    assertThrows(Problem.class, () -> service.create(null, List.of(-3L), List.of(7L), "申请"));
    assertThrows(Problem.class, () -> service.create(null, Arrays.asList(3L, null), List.of(7L), "申请"));
    assertThrows(Problem.class, () -> service.create(null, List.of(3L), List.of(7L), "  "));
    assertThrows(Problem.class, () -> service.create(null, List.of(3L), List.of(7L), "字".repeat(1001)));
    assertThrows(Problem.class, () -> service.create(null, Collections.nCopies(101, 3L), List.of(7L), "申请"));
    verify(db, never()).create(anyMap());
  }

  @Test
  void httpDtosRejectNullMembersMissingDecisionAndUnboundedInputs() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertFalse(validator.validate(new LearningRequestController.Create(null,
          Arrays.asList(3L, null), List.of(7L), "申请")).isEmpty());
      assertFalse(validator.validate(new LearningRequestController.Create(0L,
          List.of(3L), List.of(7L), "申请")).isEmpty());
      assertFalse(validator.validate(new LearningRequestController.Review(null, "同意")).isEmpty());
      assertTrue(validator.validate(new LearningRequestController.Review(false, "驳回")).isEmpty());
      assertFalse(validator.validate(new LearningRequestController.Grant(
          List.of(3L), Collections.nCopies(1001, 7L), "申请")).isEmpty());
    }
  }

  @Test
  void allNewMapperStatementsParse() {
    Configuration configuration = new Configuration();
    configuration.addMapper(LearningRequestMapper.class);
    assertTrue(configuration.hasStatement(LearningRequestMapper.class.getName() + ".eligibleUsers"));
    assertTrue(configuration.hasStatement(LearningRequestMapper.class.getName() + ".review"));
  }
}
