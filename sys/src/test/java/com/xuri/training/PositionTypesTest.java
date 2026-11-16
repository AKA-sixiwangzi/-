package com.xuri.training;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.IdentityMapper;
import com.xuri.training.security.*;
import com.xuri.training.service.IdentityService;
import com.xuri.training.web.IdentityController;
import com.xuri.training.web.Requests;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PositionTypesTest {
  IdentityMapper db = mock(IdentityMapper.class);
  IdentityService service = mock(IdentityService.class);
  IdentityController controller = new IdentityController(service, db);
  List<Map<String, Object>> fixed = List.of(
      row("id", 17L, "name", "专业岗", "position_type", "PROFESSIONAL"),
      row("id", 23L, "name", "管理岗", "position_type", "MANAGEMENT"),
      row("id", 42L, "name", "综合岗", "position_type", "GENERAL"));

  @BeforeEach
  void setup() {
    Current.set(new Actor(1, Set.of("EMPLOYEE", "ADMIN")));
    when(db.positions()).thenReturn(fixed);
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void organizationReturnsOnlyTheThreeFixedPositionsWithActualDatabaseIds() {
    List<Map<String, Object>> legacy = new ArrayList<>(fixed);
    legacy.add(row("id", 99L, "name", "工程师", "position_type", "PROFESSIONAL"));
    legacy.add(row("id", 100L, "name", "专业岗", "position_type", "GENERAL"));
    when(db.positions()).thenReturn(legacy);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) controller.organization();
    assertEquals(fixed, result.get("positions"));
  }

  @Test
  void canonicalPositionCheckRejectsOtherNamesAndMismatchedCodes() {
    assertTrue(fixed.stream().allMatch(IdentityMapper::fixedPosition));
    assertFalse(IdentityMapper.fixedPosition(row("name", "工程师", "position_type", "PROFESSIONAL")));
    assertFalse(IdentityMapper.fixedPosition(row("name", "管理岗", "position_type", "PROFESSIONAL")));
    assertFalse(IdentityMapper.fixedPosition(row("name", "专业岗", "position_type", "INSTRUCTOR")));
    assertFalse(IdentityMapper.fixedPosition(row("id", 123L)));
  }

  @Test
  void administratorCannotCreateAnyAdditionalPosition() {
    Problem error = assertThrows(Problem.class,
        () -> controller.position(new Requests.Position("工程师", "PROFESSIONAL")));
    assertEquals(409, error.status);
    assertEquals("岗位固定为专业岗、管理岗、综合岗，不支持新增或修改", error.getMessage());
    verifyNoInteractions(db);
  }

  @Test
  void administratorCannotRenameOrReclassifyAnExistingPosition() {
    assertEquals(409, assertThrows(Problem.class,
        () -> controller.positionUpdate(17, new Requests.Position("工程师", "PROFESSIONAL"))).status);
    assertEquals(409, assertThrows(Problem.class,
        () -> controller.positionUpdate(17, new Requests.Position("专业岗", "GENERAL"))).status);
    verifyNoInteractions(db);
  }

  @Test
  void employeesCannotUseDisabledPositionWriteEndpoints() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    assertEquals(403, assertThrows(Problem.class,
        () -> controller.position(new Requests.Position("工程师", "PROFESSIONAL"))).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> controller.positionUpdate(17, null)).status);
    verifyNoInteractions(db);
  }

  @Test
  void oldHttpClientsReceiveClearConflictForEveryPositionWrite() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new Errors()).build();
    mvc.perform(post("/api/admin/positions").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"工程师\",\"positionType\":\"OTHER\"}"))
        .andExpect(status().isConflict());
    mvc.perform(put("/api/admin/positions/17").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"管理岗\"}"))
        .andExpect(status().isConflict());
    mvc.perform(post("/api/admin/positions")).andExpect(status().isConflict());
    verifyNoInteractions(db);
  }

  @Test
  void rolePermissionIsCheckedBeforeDisabledEndpointPayloadFields() throws Exception {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new Errors()).build();
    mvc.perform(post("/api/admin/positions").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\",\"positionType\":\"OTHER\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(put("/api/admin/positions/17")).andExpect(status().isForbidden());
    verifyNoInteractions(db);
  }

  @Test
  void userCannotBeCreatedWithUnknownPositionId() {
    assertEquals(400, assertThrows(Problem.class,
        () -> controller.create(new Requests.User("staff", "longPassword1!", "员工", null, 999L, null))).status);
    verifyNoInteractions(service);
  }

  @Test
  void customLegacyPositionIsRejectedEvenIfItHasAValidTypeCode() {
    when(db.positions()).thenReturn(List.of(
        row("id", 99L, "name", "工程师", "position_type", "PROFESSIONAL")));
    assertEquals(400, assertThrows(Problem.class,
        () -> controller.create(new Requests.User("staff", "longPassword1!", "员工", null, 99L, null))).status);
    verifyNoInteractions(service);
  }

  @Test
  void fixedPositionSelectionPassesThroughItsRealId() {
    controller.create(new Requests.User("staff", "longPassword1!", "员工", null, 17L, null));
    verify(service).create("staff", "longPassword1!", "员工", null, 17L, null);
  }

  @Test
  void selectingManagementPositionDoesNotGrantDepartmentLeaderRole() {
    when(db.profileUpdate(anyMap())).thenReturn(1);
    controller.profile(7, new Requests.UserProfile("员工", null, 23L, null));
    verify(db).profileUpdate(row("id", 7L, "display_name", "员工", "department_id", null,
        "position_id", 23L, "grade_id", null));
    verify(db, never()).grant(anyLong(), anyString());
    verify(db, never()).ungrant(anyLong(), anyString());
    verifyNoInteractions(service);
  }

  @Test
  void rolesRemainSeparateFromTheThreePositions() {
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) controller.organization();
    assertEquals(List.of(
        row("code", "EMPLOYEE", "name", "普通员工"),
        row("code", "INSTRUCTOR", "name", "内训师"),
        row("code", "MANAGER", "name", "部门负责人"),
        row("code", "ADMIN", "name", "组织发展部")), result.get("roles"));
  }

  @Test
  void employeeListExposesAssignedRolesAndExplicitManagerScopes() {
    when(db.users(0)).thenReturn(List.of(row("id", 11L, "display_name", "负责人")));
    when(db.roles(11)).thenReturn(Set.of("EMPLOYEE", "MANAGER"));
    when(db.managerDepartmentIds(11)).thenReturn(List.of(2L));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> result = (List<Map<String, Object>>) controller.users(0);
    assertEquals(Set.of("EMPLOYEE", "MANAGER"), result.get(0).get("roles"));
    assertEquals(List.of(2L), result.get(0).get("manager_department_ids"));
  }
}
