package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuri.training.common.Problem;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.web.Requests;
import java.util.*;
import org.junit.jupiter.api.*;

class PathDepartmentRulesTest {
  ProgramMapper db = mock(ProgramMapper.class);
  CourseMapper courses = mock(CourseMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  WarningMapper messages = mock(WarningMapper.class);
  ExamMapper exams = mock(ExamMapper.class);
  ProgramService service = new ProgramService(db, courses, users, messages, exams);

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    Current.set(new Actor(1, Set.of("ADMIN")));
    when(users.departments()).thenReturn(
        List.of(row("id", 1L, "name", "总经办"), row("id", 2L, "name", "运营管理部")));
    doAnswer(invocation -> {
      ((Map<String, Object>) invocation.getArgument(0)).put("id", 10L);
      return null;
    }).when(db).createPath(anyMap());
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void oldClientRequestDefaultsToAllDepartments() throws Exception {
    Requests.Path request = new ObjectMapper().readValue(
        "{\"title\":\"旧客户端路线\",\"positionId\":null,\"gradeId\":null}", Requests.Path.class);
    Map<String, Object> result = service.path(
        request.title(), request.positionId(), request.gradeId(),
        request.allDepartments(), request.departmentIds());
    assertEquals(true, result.get("all_departments"));
    assertEquals(List.of(), result.get("department_ids"));
    verify(db, never()).pathDepartment(anyLong(), anyLong());
  }

  @Test
  void legacyServiceEntryAlsoKeepsAllDepartmentDefault() {
    assertEquals(true, service.path("原有路线", null, null).get("all_departments"));
  }

  @Test
  void multipleDepartmentsAreValidatedDeduplicatedAndStored() {
    Map<String, Object> result = service.path("部门路线", null, null, false, List.of(2L, 1L, 2L));
    assertEquals(false, result.get("all_departments"));
    assertEquals(List.of(1L, 2L), result.get("department_ids"));
    assertEquals(List.of(row("id", 1L, "name", "总经办"), row("id", 2L, "name", "运营管理部")),
        result.get("departments"));
    verify(db).pathDepartment(10, 1);
    verify(db).pathDepartment(10, 2);
    verify(db, times(2)).pathDepartment(anyLong(), anyLong());
  }

  @Test
  void selectedIdsWithoutFlagInferTargetedDepartments() {
    assertEquals(false, service.path("部门路线", null, null, null, List.of(1L)).get("all_departments"));
  }

  @Test
  void allDepartmentsCannotCarryContradictoryExplicitIds() {
    assertEquals(400, assertThrows(Problem.class,
        () -> service.path("矛盾范围", null, null, true, List.of(1L))).status);
    verify(db, never()).createPath(anyMap());
  }

  @Test
  void targetedDepartmentsRequireAtLeastOneChoice() {
    assertThrows(Problem.class, () -> service.path("空范围", null, null, false, List.of()));
    assertThrows(Problem.class, () -> service.path("空范围", null, null, false, null));
    verify(db, never()).createPath(anyMap());
  }

  @Test
  void invalidOrUnknownDepartmentsFailBeforeWritingPath() {
    assertThrows(Problem.class, () -> service.path("未知部门", null, null, false, List.of(99L)));
    assertThrows(Problem.class, () -> service.path("负数部门", null, null, false, List.of(-1L)));
    assertThrows(Problem.class,
        () -> service.path("空部门编号", null, null, false, Arrays.asList(1L, null)));
    verify(db, never()).createPath(anyMap());
    verify(db, never()).pathDepartment(anyLong(), anyLong());
  }

  @Test
  void employeeCannotCreateDepartmentScopedRoute() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    assertEquals(403, assertThrows(Problem.class,
        () -> service.path("部门路线", null, null, false, List.of(1L))).status);
    verify(db, never()).createPath(anyMap());
  }

  @Test
  void listAndDetailExposeSameDepartmentNamesAndIds() {
    Map<String, Object> path = row("id", 10L, "title", "总经办路线", "all_departments", false);
    List<Map<String, Object>> departments = List.of(row("id", 1L, "name", "总经办"));
    when(db.paths(true, 1)).thenReturn(List.of(path));
    when(db.path(10)).thenReturn(path);
    when(db.pathDepartments(10)).thenReturn(departments);
    Map<String, Object> listed = service.paths().get(0);
    assertEquals(false, listed.get("all_departments"));
    assertEquals(List.of(1L), listed.get("department_ids"));
    assertEquals(departments, listed.get("departments"));
    assertEquals(listed, service.detail(10, 1).get("path"));
  }

  @Test
  void legacyRoutesWithoutScopeRemainAllDepartments() {
    when(db.paths(true, 1)).thenReturn(List.of(row("id", 10L, "title", "旧路线")));
    Map<String, Object> result = service.paths().get(0);
    assertEquals(true, result.get("all_departments"));
    assertEquals(List.of(), result.get("department_ids"));
    verify(db, never()).pathDepartments(anyLong());
  }

  @Test
  void allDepartmentRouteAcceptsEmployeesWithoutDepartment() {
    when(db.lockPath(10)).thenReturn(row("id", 10L, "published", true, "all_departments", true));
    when(users.lockUser(7)).thenReturn(row("id", 7L, "department_id", null, "active", true));
    service.assign(10, List.of(7L));
    verify(db).assign(10, 7);
    verify(db, never()).pathDepartments(anyLong());
  }

  @Test
  void matchingDepartmentEmployeesCanBeAssigned() {
    when(db.lockPath(10)).thenReturn(row("id", 10L, "published", true, "all_departments", false));
    when(db.pathDepartments(10)).thenReturn(List.of(row("id", 1L, "name", "总经办")));
    when(users.lockUser(7)).thenReturn(row("id", 7L, "department_id", 1L, "active", true));
    service.assign(10, List.of(7L, 7L));
    verify(db, times(1)).assign(10, 7);
  }

  @Test
  void wholeBatchIsValidatedBeforeAnyAssignmentOrNotification() {
    when(db.lockPath(10)).thenReturn(row("id", 10L, "published", true, "all_departments", false));
    when(db.pathDepartments(10)).thenReturn(List.of(row("id", 1L, "name", "总经办")));
    when(users.lockUser(7)).thenReturn(row("id", 7L, "department_id", 1L, "active", true));
    when(users.lockUser(8)).thenReturn(row("id", 8L, "department_id", 2L, "active", true));
    assertEquals(400, assertThrows(Problem.class, () -> service.assign(10, List.of(7L, 8L))).status);
    verify(db, never()).assign(anyLong(), anyLong());
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(messages, never()).notify(anyLong(), anyString(), anyString());
  }

  @Test
  void targetedRouteRejectsEmployeesWithoutDepartment() {
    when(db.lockPath(10)).thenReturn(row("id", 10L, "published", true, "all_departments", false));
    when(db.pathDepartments(10)).thenReturn(List.of(row("id", 1L, "name", "总经办")));
    when(users.lockUser(7)).thenReturn(row("id", 7L, "department_id", null, "active", true));
    assertThrows(Problem.class, () -> service.assign(10, List.of(7L)));
    verify(db, never()).assign(anyLong(), anyLong());
  }
}
