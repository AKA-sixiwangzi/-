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
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InitialPasswordAccessTest {
  IdentityMapper db = mock(IdentityMapper.class);
  IdentityService service = mock(IdentityService.class);
  MockMvc mvc;

  @BeforeEach
  void setup() {
    when(db.authenticate(AuthInterceptor.hash("initial-token"))).thenReturn(7L);
    when(db.roles(7)).thenReturn(Set.of("EMPLOYEE", "ADMIN"));
    when(db.mustChangePassword(7)).thenReturn(true);
    when(db.profile(7)).thenReturn(row("id", 7L, "must_change_password", true));
    mvc = MockMvcBuilders.standaloneSetup(new IdentityController(service, db))
        .setControllerAdvice(new Errors()).addInterceptors(new AuthInterceptor(db)).build();
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void initialPasswordAccountCanReadItsRequirementChangePasswordAndLogout() throws Exception {
    String body = mvc.perform(get("/api/me").header("Authorization", "Bearer initial-token"))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    assertTrue(body.contains("\"must_change_password\":true"));
    mvc.perform(put("/api/auth/password").header("Authorization", "Bearer initial-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"oldPassword\":\"OriginalPassword123\",\"newPassword\":\"ReplacementPassword123\"}"))
        .andExpect(status().isOk());
    verify(service).changePassword("OriginalPassword123", "ReplacementPassword123");
    mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer initial-token"))
        .andExpect(status().isOk());
    verify(db).revoke(AuthInterceptor.hash("initial-token"));
  }

  @Test
  void firstLoginCannotBypassPasswordChangeEvenWithAdministratorRole() throws Exception {
    mvc.perform(get("/api/admin/users").header("Authorization", "Bearer initial-token"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/organization").header("Authorization", "Bearer initial-token"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/admin/employees").header("Authorization", "Bearer initial-token")
            .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"员工\"}"))
        .andExpect(status().isForbidden());
    verify(db, never()).users(anyInt());
    verifyNoInteractions(service);
    assertThrows(Problem.class, Current::actor);
  }

  @Test
  void generatedEmployeeEndpointNeedsOnlyEmployeeFieldsAndValidatesOrganization() throws Exception {
    when(db.mustChangePassword(7)).thenReturn(false);
    when(db.departments()).thenReturn(List.of(row("id", 2L)));
    when(db.positions()).thenReturn(List.of(row("id", 17L, "name", "专业岗", "position_type", "PROFESSIONAL")));
    when(db.grades()).thenReturn(List.of(row("id", 3L)));
    when(service.employee("新员工", 2L, 17L, 3L)).thenReturn(row("user", row("id", 11L)));
    mvc.perform(post("/api/admin/employees").header("Authorization", "Bearer initial-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"displayName\":\"新员工\",\"departmentId\":2,\"positionId\":17,\"gradeId\":3}"))
        .andExpect(status().isOk());
    verify(service).employee("新员工", 2L, 17L, 3L);
    mvc.perform(post("/api/admin/employees").header("Authorization", "Bearer initial-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"displayName\":\"其他员工\",\"departmentId\":999}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/admin/employees").header("Authorization", "Bearer initial-token")
            .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"\"}"))
        .andExpect(status().isBadRequest());
    verify(service, times(1)).employee(anyString(), any(), any(), any());
  }

  @Test
  void existingManualAccountEndpointRemainsAvailableAfterPasswordRestrictionClears() throws Exception {
    when(db.mustChangePassword(7)).thenReturn(false);
    mvc.perform(post("/api/admin/users").header("Authorization", "Bearer initial-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"legacy.staff\",\"password\":\"LegacyPassword123\",\"displayName\":\"旧方式员工\"}"))
        .andExpect(status().isOk());
    verify(service).create("legacy.staff", "LegacyPassword123", "旧方式员工", null, null, null);
  }
}
