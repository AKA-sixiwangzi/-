package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.web.ProgramController;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PathAssignmentScopeTest {
  ProgramMapper db = mock(ProgramMapper.class);
  CourseMapper courses = mock(CourseMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  WarningMapper messages = mock(WarningMapper.class);
  ProgramService service = new ProgramService(db, courses, users, messages, mock(ExamMapper.class));

  @BeforeEach
  void setup() {
    Current.set(new Actor(1, Set.of("ADMIN")));
    when(users.departments()).thenReturn(List.of(row("id", 1L), row("id", 2L)));
    when(users.positions()).thenReturn(List.of(row("id", 2L), row("id", 3L)));
    when(db.lockPath(10)).thenReturn(
        row("id", 10L, "title", "专业路线", "published", true,
            "position_id", 2L, "all_departments", true));
    when(users.lockUser(7)).thenReturn(employee(7, 1, 2, true));
    when(users.lockUser(8)).thenReturn(employee(8, 1, 2, true));
  }

  private Map<String, Object> employee(long id, long department, long position, boolean active) {
    return row("id", id, "department_id", department, "position_id", position, "active", active);
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  private void noAssignments() {
    verify(db, never()).assign(anyLong(), anyLong());
    verify(courses, never()).enroll(anyLong(), anyLong());
    verify(messages, never()).notify(anyLong(), anyString(), anyString());
  }

  @Test
  void matchingDepartmentAndSpecificPositionAssignsDeduplicatedMembers() {
    service.assign(10, List.of(7L, 7L), 1L, 2L);
    verify(db, times(1)).assign(10, 7);
    verify(messages, times(1)).notify(7, "新的岗位成长路线", "专业路线");
  }

  @Test
  void bothDepartmentAndPositionAreRequired() {
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L), null, 2L)).status);
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L), 1L, null)).status);
    noAssignments();
  }

  @Test
  void unknownDepartmentOrPositionCannotBeForged() {
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L), 99L, 2L)).status);
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L), 1L, 99L)).status);
    noAssignments();
  }

  @Test
  void selectedPositionMustFitRoutePosition() {
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L), 1L, 3L)).status);
    noAssignments();
  }

  @Test
  void anotherDepartmentInSameBatchPreventsEveryWrite() {
    when(users.lockUser(8)).thenReturn(employee(8, 2, 2, true));
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L, 8L), 1L, 2L)).status);
    noAssignments();
  }

  @Test
  void anotherSpecificPositionInSameBatchPreventsEveryWrite() {
    when(users.lockUser(8)).thenReturn(employee(8, 1, 3, true));
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L, 8L), 1L, 2L)).status);
    noAssignments();
  }

  @Test
  void inactiveEmployeeCannotReceiveRoute() {
    when(users.lockUser(8)).thenReturn(employee(8, 1, 2, false));
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L, 8L), 1L, 2L)).status);
    noAssignments();
  }

  @Test
  void existingDepartmentScopeStillAppliesWithChosenFilters() {
    when(db.lockPath(10)).thenReturn(
        row("id", 10L, "published", true, "position_id", 2L, "all_departments", false));
    when(db.pathDepartments(10)).thenReturn(List.of(row("id", 2L)));
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L), 1L, 2L)).status);
    noAssignments();
  }

  @Test
  void legacyServiceCallAlsoEnforcesRouteSpecificPosition() {
    when(users.lockUser(7)).thenReturn(employee(7, 1, 3, true));
    assertEquals(400, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L))).status);
    noAssignments();
  }

  @Test
  void departmentLeaderCannotBypassOrganizationDevelopmentApproval() {
    Current.set(new Actor(11, Set.of("EMPLOYEE", "MANAGER")));
    assertEquals(403, assertThrows(Problem.class,
        () -> service.assign(10, List.of(7L), 1L, 2L)).status);
    noAssignments();
  }

  @Test
  void routeMemberHttpPayloadRequiresBothFiltersAndValidMembers() throws Exception {
    ProgramService mockService = mock(ProgramService.class);
    var mvc = MockMvcBuilders.standaloneSetup(new ProgramController(mockService, db))
        .setControllerAdvice(new Errors()).build();
    mvc.perform(post("/api/paths/10/assign").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userIds\":[7]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/paths/10/assign").contentType(MediaType.APPLICATION_JSON)
            .content("{\"departmentId\":1,\"positionId\":2,\"userIds\":[null]}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(mockService);
    mvc.perform(post("/api/paths/10/assign").contentType(MediaType.APPLICATION_JSON)
            .content("{\"departmentId\":1,\"positionId\":2,\"userIds\":[7]}"))
        .andExpect(status().isOk());
    verify(mockService).assign(10, List.of(7L), 1L, 2L);
  }
}
