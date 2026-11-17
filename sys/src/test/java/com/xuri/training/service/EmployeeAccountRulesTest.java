package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.common.Problem;
import com.xuri.training.mapper.IdentityMapper;
import com.xuri.training.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class EmployeeAccountRulesTest {
  IdentityMapper db = mock(IdentityMapper.class);
  IdentityService service = new IdentityService(db);
  List<Map<String, Object>> persisted = new ArrayList<>();

  @BeforeEach
  void setup() {
    Current.set(new Actor(1, Set.of("EMPLOYEE", "ADMIN")));
    doAnswer(call -> {
      Map<String, Object> user = call.getArgument(0);
      user.put("id", 10L + persisted.size());
      persisted.add(new HashMap<>(user));
      return null;
    }).when(db).createEmployee(anyMap());
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  @SuppressWarnings("unchecked")
  @Test
  void employeeCreationIssuesOneTimeCredentialsAndPersistsOnlyBcrypt() {
    when(db.byName(anyString())).thenReturn(null);
    Map<String, Object> result = service.employee("张老师", 2L, 17L, 3L);
    Map<String, Object> employee = (Map<String, Object>) result.get("user");
    Map<String, Object> account = (Map<String, Object>) result.get("account");
    String password = (String) account.get("temporaryPassword");
    assertTrue(((String) account.get("username")).matches("EMP[A-F0-9]{24}"));
    assertEquals(employee.get("username"), account.get("username"));
    assertTrue(password.length() >= 16);
    assertTrue(password.chars().anyMatch(Character::isUpperCase));
    assertTrue(password.chars().anyMatch(Character::isLowerCase));
    assertTrue(password.chars().anyMatch(Character::isDigit));
    assertTrue(new BCryptPasswordEncoder().matches(password, (String) persisted.get(0).get("password_hash")));
    assertFalse(persisted.get(0).containsValue(password));
    assertFalse(employee.containsKey("password_hash"));
    assertFalse(employee.containsKey("temporaryPassword"));
    assertEquals(true, employee.get("must_change_password"));
    assertEquals(true, account.get("requiresPasswordChange"));
    assertEquals(2L, persisted.get(0).get("department_id"));
    assertEquals(17L, persisted.get(0).get("position_id"));
    assertEquals(3L, persisted.get(0).get("grade_id"));
    verify(db).createEmployee(anyMap());
    verify(db, never()).create(anyMap());
    verify(db).grant(10, "EMPLOYEE");
    verify(db).audit(1L, "EMPLOYEE_CREATE", "10", (String) employee.get("username"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void usernameCollisionIsSkippedAndNewEmployeesReceiveDifferentCredentials() {
    when(db.byName(anyString())).thenReturn(row("id", 99L), null);
    Map<String, Object> first = (Map<String, Object>) service.employee("员工甲", null, null, null).get("account");
    Map<String, Object> second = (Map<String, Object>) service.employee("员工乙", null, null, null).get("account");
    assertNotEquals(first.get("username"), second.get("username"));
    assertNotEquals(first.get("temporaryPassword"), second.get("temporaryPassword"));
    verify(db, times(3)).byName(anyString());
    assertEquals(2, persisted.size());
  }

  @Test
  void employeeCannotCreateAccountsAndInvalidNameDoesNotWriteAnything() {
    assertEquals(400, assertThrows(Problem.class, () -> service.employee(" ", null, null, null)).status);
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    assertEquals(403, assertThrows(Problem.class, () -> service.employee("员工", null, null, null)).status);
    verifyNoInteractions(db);
  }

  @Test
  void passwordChangeRejectsWrongOrUnchangedPasswordWithoutClearingRestriction() {
    String original = "OriginalPassword123";
    when(db.lockUser(1)).thenReturn(row("password_hash", new BCryptPasswordEncoder(4).encode(original)));
    assertEquals(400, assertThrows(Problem.class, () -> service.changePassword("incorrect", "ReplacementPassword123")).status);
    assertEquals(400, assertThrows(Problem.class, () -> service.changePassword(original, original)).status);
    assertEquals(400, assertThrows(Problem.class, () -> service.changePassword(original, "short")).status);
    verify(db, never()).password(anyLong(), anyString());
    verify(db, never()).revokeAll(anyLong());
  }

  @Test
  void successfulPasswordChangeWritesNewHashAndRevokesEverySession() {
    String original = "OriginalPassword123";
    String replacement = "ReplacementPassword456";
    when(db.lockUser(1)).thenReturn(row("password_hash", new BCryptPasswordEncoder(4).encode(original)));
    service.changePassword(original, replacement);
    var hash = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(db).password(eq(1L), hash.capture());
    assertTrue(new BCryptPasswordEncoder().matches(replacement, hash.getValue()));
    assertFalse(new BCryptPasswordEncoder().matches(original, hash.getValue()));
    verify(db).revokeAll(1);
  }

  @SuppressWarnings("unchecked")
  @Test
  void loginIncludesPasswordChangeRequirementWithoutReturningPasswordHash() {
    when(db.loginGuard("EMP001")).thenReturn(row("failures", 0));
    when(db.byName("EMP001")).thenReturn(row("id", 7L, "active", true,
        "password_hash", new BCryptPasswordEncoder(4).encode("InitialPassword123")));
    when(db.profile(7)).thenReturn(row("id", 7L, "username", "EMP001", "must_change_password", true));
    when(db.roles(7)).thenReturn(Set.of("EMPLOYEE"));
    Map<String, Object> result = service.login("EMP001", "InitialPassword123");
    Map<String, Object> profile = (Map<String, Object>) result.get("user");
    assertEquals(true, profile.get("must_change_password"));
    assertFalse(profile.containsKey("password_hash"));
    assertFalse(result.containsKey("temporaryPassword"));
    assertNotNull(result.get("token"));
  }
}
