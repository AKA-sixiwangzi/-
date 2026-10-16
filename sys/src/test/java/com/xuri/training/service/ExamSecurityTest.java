package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ExamSecurityTest {
  ExamMapper db = mock(ExamMapper.class);
  CourseService courses = mock(CourseService.class);
  IdentityMapper users = mock(IdentityMapper.class);
  ProgramService paths = mock(ProgramService.class);
  ProgramMapper programs = mock(ProgramMapper.class);
  WarningMapper messages = mock(WarningMapper.class);
  ExamService service =
      new ExamService(
          db,
          courses,
          users,
          paths,
          programs,
          messages,
          new ObjectMapper(),
          Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC));

  @BeforeEach
  void set() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void directExamStartCannotBypassLearningGate() {
    when(db.examById(9)).thenReturn(row("id", 9L));
    when(db.eligible(9, 7)).thenReturn(1);
    when(db.prerequisites(9, 7))
        .thenReturn(List.of(row("id", 2L, "completed", 0, "status", "PUBLISHED")));
    assertEquals(409, assertThrows(Problem.class, () -> service.start(9)).status);
    verify(db, never()).attempt(anyMap());
  }

  @Test
  void answersNeverAppearInLearnerQuestionResponse() {
    when(db.attemptById("a")).thenReturn(row("id", "a", "user_id", 7L, "exam_id", 9L));
    when(db.examById(9)).thenReturn(row("paper_id", 2L));
    String snapshot =
        service.encode(
            row(
                "type",
                "SINGLE",
                "stem",
                "题目",
                "options_json",
                "{\"A\":\"正确\",\"B\":\"错误\"}",
                "answer",
                "A",
                "explanation",
                "秘密解析"));
    when(db.paperQuestions(2))
        .thenReturn(List.of(row("id", 3L, "score", 5, "snapshot_json", snapshot)));
    String serialized = service.encode(service.attemptView("a"));
    assertFalse(serialized.contains("秘密解析"));
    assertFalse(serialized.contains("snapshot_json"));
    assertFalse(serialized.contains("\"answer\":\"A\""));
  }

  @Test
  void savingAnotherUsersAttemptIsForbidden() {
    when(db.attemptById("a")).thenReturn(row("user_id", 8L));
    assertEquals(403, assertThrows(Problem.class, () -> service.save("a", 2, "A")).status);
  }

  @Test
  void savingAfterServerDeadlineIsForbidden() {
    when(db.attemptById("a"))
        .thenReturn(row("user_id", 7L, "status", "IN_PROGRESS", "deadline_ms", 999L));
    when(db.lockAttempt("a")).thenReturn(row("id", "a"));
    assertEquals(409, assertThrows(Problem.class, () -> service.save("a", 2, "A")).status);
    verify(db, never()).answer(anyString(), anyLong(), anyString());
  }

  @Test
  void selfGradingIsForbiddenEvenForAdministrator() {
    Current.set(new Actor(7, Set.of("ADMIN")));
    when(db.lockAttempt("a")).thenReturn(row("user_id", 7L, "exam_id", 9L));
    when(db.examById(9)).thenReturn(row("owner_id", 7L));
    assertEquals(403, assertThrows(Problem.class, () -> service.grade("a", 2, 10)).status);
  }

  @Test
  void choiceValidationAndExactScoring() {
    assertTrue(QuestionRules.correct("A,C", "C,A"));
    assertFalse(QuestionRules.correct("A,C", "A"));
    assertFalse(QuestionRules.correct("A", "A,B"));
    assertThrows(
        Problem.class, () -> QuestionRules.validate("SINGLE", Map.of("A", "a", "B", "b"), "C"));
  }
}
