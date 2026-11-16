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
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PositionTypesTest {
  IdentityMapper db = mock(IdentityMapper.class);
  IdentityService service = mock(IdentityService.class);
  IdentityController controller = new IdentityController(service, db);

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    Current.set(new Actor(1, Set.of("EMPLOYEE", "ADMIN")));
    doAnswer(invocation -> {
      ((Map<String, Object>) invocation.getArgument(0)).put("id", 9L);
      return null;
    }).when(db).position(anyMap());
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void concreteJobNameIsPreservedWithinEachFixedType() {
    for (String type : List.of("PROFESSIONAL", "MANAGEMENT", "GENERAL")) {
      @SuppressWarnings("unchecked")
      Map<String, Object> result =
          (Map<String, Object>) controller.position(new Requests.Position("工程师", type));
      assertEquals("工程师", result.get("name"));
      assertEquals(type, result.get("position_type"));
    }
  }

  @Test
  void legacyPositionCreationDefaultsToGeneralWithoutRenamingJob() {
    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) controller.position(new Requests.Position("旧文员岗", null));
    assertEquals("旧文员岗", result.get("name"));
    assertEquals("GENERAL", result.get("position_type"));
  }

  @Test
  void unsupportedPositionTypeFailsBeforeWrite() {
    assertEquals(400, assertThrows(Problem.class,
        () -> controller.position(new Requests.Position("工程师", "INSTRUCTOR"))).status);
    verify(db, never()).position(anyMap());
  }

  @Test
  void existingPositionCanBeReclassifiedWithoutChangingIdentity() {
    when(db.positionUpdate(anyMap())).thenReturn(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> result =
        (Map<String, Object>) controller.positionUpdate(4, new Requests.Position("工程师", "PROFESSIONAL"));
    assertEquals(4L, result.get("id"));
    assertEquals("PROFESSIONAL", result.get("position_type"));
    verify(db).audit(1L, "POSITION_UPDATE", "4", "工程师");
  }

  @Test
  void nonexistentPositionCannotBeUpdated() {
    assertEquals(404, assertThrows(Problem.class,
        () -> controller.positionUpdate(404, new Requests.Position("工程师", "PROFESSIONAL"))).status);
    verify(db, never()).audit(any(), anyString(), anyString(), anyString());
  }

  @Test
  void employeesCannotCreateOrReclassifyPositions() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    assertEquals(403, assertThrows(Problem.class,
        () -> controller.position(new Requests.Position("工程师", "PROFESSIONAL"))).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> controller.positionUpdate(4, new Requests.Position("工程师", "PROFESSIONAL"))).status);
    verify(db, never()).position(anyMap());
    verify(db, never()).positionUpdate(anyMap());
  }

  @Test
  void employeeCannotBeCreatedWithUnknownConcretePosition() {
    assertEquals(400, assertThrows(Problem.class,
        () -> controller.create(new Requests.User("staff", "longPassword1!", "员工", null, 999L, null))).status);
    verifyNoInteractions(service);
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

  @Test
  void httpPositionPayloadChecksTypeAndPreservesExactJobName() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new Errors()).build();
    mvc.perform(post("/api/admin/positions").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"工程师\",\"positionType\":\"INSTRUCTOR\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/admin/positions").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"工程师\",\"positionType\":\"PROFESSIONAL\"}"))
        .andExpect(status().isOk());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captured = ArgumentCaptor.forClass(Map.class);
    verify(db).position(captured.capture());
    assertEquals("工程师", captured.getValue().get("name"));
    assertEquals("PROFESSIONAL", captured.getValue().get("position_type"));
  }
}
