package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.util.*;
import org.junit.jupiter.api.*;

class WarningWorkflowTest {
  WarningMapper db = mock(WarningMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  WarningService service = new WarningService(db, users);

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void warningIsMergedWithoutSendingRepeatedNotifications() {
    when(db.recent(7, "MULTI_PLAY")).thenReturn(9L);
    service.raise(7, 2, "s", "MULTI_PLAY", "evidence");
    verify(db).repeat(9);
    verify(db, never()).notify(anyLong(), anyString(), anyString());
  }

  @Test
  void measuresPreserveBeforeAndAfterState() {
    Current.set(new Actor(1, Set.of("ADMIN")));
    when(db.lock(9)).thenReturn(row("user_id", 7L, "status", "INVESTIGATING"));
    service.action(9, "WARN", "已联系员工说明学习规则");
    verify(db)
        .action(
            argThat(
                r ->
                    r.get("before").equals("INVESTIGATING")
                        && r.get("after").equals("REVIEW")
                        && r.get("note").equals("已联系员工说明学习规则")));
    verify(db).state(9, "REVIEW", 1);
  }

  @Test
  void sameAdministratorCannotCloseTheirOwnHandling() {
    Current.set(new Actor(1, Set.of("ADMIN")));
    when(db.lock(9)).thenReturn(row("user_id", 7L, "status", "REVIEW", "handler_id", 1L));
    assertThrows(Problem.class, () -> service.action(9, "CLOSE", "本人复核关闭"));
  }

  @Test
  void independentAdministratorCanReviewAndClose() {
    Current.set(new Actor(2, Set.of("ADMIN")));
    when(db.lock(9)).thenReturn(row("user_id", 7L, "status", "REVIEW", "handler_id", 1L));
    service.action(9, "CLOSE", "已复核证据和处理措施");
    verify(db).state(9, "CLOSED", 2);
  }

  @Test
  void employeeCanAppealOnlyTheirOwnClosedWarning() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    when(db.lock(9)).thenReturn(row("user_id", 7L, "status", "CLOSED"));
    service.action(9, "APPEAL", "当时出现网络异常，请核实");
    verify(db).state(9, "INVESTIGATING", 7);
    Current.set(new Actor(8, Set.of("EMPLOYEE")));
    assertThrows(Problem.class, () -> service.action(9, "APPEAL", "这不是本人的记录"));
  }
}
