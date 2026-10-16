package com.xuri.training;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuri.training.common.Errors;
import com.xuri.training.mapper.IdentityMapper;
import com.xuri.training.security.*;
import com.xuri.training.service.IdentityService;
import com.xuri.training.web.IdentityController;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WebAuthorizationTest {
  IdentityMapper users = mock(IdentityMapper.class);
  IdentityService service = mock(IdentityService.class);
  MockMvc mvc;

  @BeforeEach
  void set() {
    mvc =
        MockMvcBuilders.standaloneSetup(new IdentityController(service, users))
            .setControllerAdvice(new Errors())
            .addInterceptors(new AuthInterceptor(users))
            .build();
    when(users.authenticate(AuthInterceptor.hash("employee-token"))).thenReturn(7L);
    when(users.roles(7)).thenReturn(Set.of("EMPLOYEE"));
    when(users.profile(7)).thenReturn(Map.of("id", 7, "display_name", "员工"));
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void anonymousRequestsAreRejected() throws Exception {
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
  }

  @Test
  void employeeSeesLearningAndContentCreationOnly() throws Exception {
    var response =
        mvc.perform(get("/api/me").header("Authorization", "Bearer employee-token"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var node = new ObjectMapper().readTree(response);
    Assertions.assertEquals(
        List.of("LEARNING", "CONTENT_CREATION"),
        new ObjectMapper().convertValue(node.get("workspaces"), List.class));
    Assertions.assertNull(CurrentActorOrNull());
  }

  private Actor CurrentActorOrNull() {
    try {
      return Current.actor();
    } catch (com.xuri.training.common.Problem e) {
      return null;
    }
  }

  @Test
  void employeeCannotListCompanyEmployees() throws Exception {
    mvc.perform(get("/api/admin/users").header("Authorization", "Bearer employee-token"))
        .andExpect(status().isForbidden());
    verify(users, never()).users(anyInt());
  }

  @Test
  void asyncExportDoesNotLeaveIdentityOnTheRequestThread() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    new AuthInterceptor(users)
        .afterConcurrentHandlingStarted(
            new org.springframework.mock.web.MockHttpServletRequest(),
            new org.springframework.mock.web.MockHttpServletResponse(),
            null);
    Assertions.assertNull(CurrentActorOrNull());
  }

  @Test
  void roleCombinationAddsWorkspacesWithoutReplacingEmployee() throws Exception {
    when(users.roles(7)).thenReturn(Set.of("EMPLOYEE", "INSTRUCTOR", "ADMIN"));
    var response =
        mvc.perform(get("/api/me").header("Authorization", "Bearer employee-token"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var workspaces = new ObjectMapper().readTree(response).get("workspaces");
    Assertions.assertEquals(4, workspaces.size());
    Assertions.assertTrue(workspaces.toString().contains("TEACHING"));
    Assertions.assertTrue(workspaces.toString().contains("ADMINISTRATION"));
  }
}
