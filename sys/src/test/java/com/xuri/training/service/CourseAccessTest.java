package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.util.*;
import org.junit.jupiter.api.*;

class CourseAccessTest {
  CourseMapper db = mock(CourseMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  CourseService service = new CourseService(db, users);

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void anyEmployeeCanCreateContentWithoutInstructorRole() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    doAnswer(
            i -> {
              ((Map<String, Object>) i.getArgument(0)).put("id", 12L);
              return null;
            })
        .when(db)
        .create(anyMap());
    var r = service.create("员工经验分享", "操作经验", "技能");
    assertEquals(7L, r.get("owner_id"));
    verify(db).create(anyMap());
  }

  @Test
  void instructorCannotViewOtherCoursesLearners() {
    Current.set(new Actor(7, Set.of("EMPLOYEE", "INSTRUCTOR")));
    when(db.course(12)).thenReturn(row("id", 12L, "owner_id", 8L, "status", "PUBLISHED"));
    assertEquals(403, assertThrows(Problem.class, () -> service.manage(12)).status);
  }

  @Test
  void ownerManagesTheirCourseEvenBeforeBecomingInstructor() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    when(db.course(12)).thenReturn(row("id", 12L, "owner_id", 7L, "status", "DRAFT"));
    assertNotNull(service.manage(12));
  }

  @Test
  void employeeCanReadOnlyAssignedPublishedCourse() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    when(db.course(12)).thenReturn(row("id", 12L, "owner_id", 8L, "status", "PUBLISHED"));
    assertThrows(Problem.class, () -> service.readable(12));
    when(db.enrolled(7, 12)).thenReturn(1);
    assertNotNull(service.readable(12));
  }

  @Test
  void approvalAddsInstructorRoleToSameEmployeeAccount() {
    Current.set(new Actor(1, Set.of("ADMIN")));
    when(db.lockCourse(12)).thenReturn(row("id", 12L, "owner_id", 7L, "status", "PENDING"));
    service.review(12, true, "资料核验通过");
    verify(users).grant(7, "INSTRUCTOR");
    verify(db).status(12, "PUBLISHED", 1L, "资料核验通过");
  }

  @Test
  void nonAdminAndSelfReviewAreRejected() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    assertThrows(Problem.class, () -> service.review(12, true, "通过"));
    Current.set(new Actor(7, Set.of("ADMIN")));
    when(db.lockCourse(12)).thenReturn(row("id", 12L, "owner_id", 7L, "status", "PENDING"));
    assertThrows(Problem.class, () -> service.review(12, true, "通过"));
    verify(users, never()).grant(anyLong(), anyString());
  }

  @Test
  void departmentManagerDoesNotGetCompanyAdminRights() {
    Current.set(new Actor(7, Set.of("MANAGER")));
    when(db.course(12)).thenReturn(row("id", 12L, "owner_id", 8L));
    assertThrows(Problem.class, () -> service.manage(12));
  }
}
