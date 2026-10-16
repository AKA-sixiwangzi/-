package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ProgramRulesTest {
  ProgramMapper db = mock(ProgramMapper.class);
  CourseMapper courses = mock(CourseMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  WarningMapper messages = mock(WarningMapper.class);
  ExamMapper exams = mock(ExamMapper.class);
  ProgramService service = new ProgramService(db, courses, users, messages, exams);

  @AfterEach
  void clear() {
    Current.clear();
  }

  @Test
  void stageNeedsBothCourseAndExamCompletion() {
    when(db.stageCourses(1, 7)).thenReturn(List.of(row("completed", 1)));
    when(db.stageExams(1, 7)).thenReturn(List.of(row("passed", 0)));
    assertFalse(service.stagePassed(1, 7));
    when(db.stageExams(1, 7)).thenReturn(List.of(row("passed", 1)));
    assertTrue(service.stagePassed(1, 7));
  }

  @Test
  void unfinishedEarlierStageBlocksLaterExam() {
    when(db.stageById(2)).thenReturn(row("id", 2L, "path_id", 10L));
    when(db.assigned(10, 7)).thenReturn(1);
    when(db.stages(10)).thenReturn(List.of(row("id", 1L), row("id", 2L)));
    when(db.stageCourses(1, 7)).thenReturn(List.of(row("completed", 0)));
    assertEquals(409, assertThrows(Problem.class, () -> service.requireStageUnlocked(2, 7)).status);
  }

  @Test
  void learnerCannotIssueCertificateForIncompletePath() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    when(db.assigned(10, 7)).thenReturn(1);
    when(db.stages(10)).thenReturn(List.of(row("id", 1L)));
    when(db.stageCourses(1, 7)).thenReturn(List.of(row("completed", 0)));
    assertThrows(Problem.class, () -> service.certificate(10));
    verify(db, never()).certificate(anyLong(), anyLong());
  }

  @Test
  void publishedRouteCannotBeEdited() {
    Current.set(new Actor(1, Set.of("ADMIN")));
    when(db.lockPath(10)).thenReturn(row("published", true));
    assertThrows(Problem.class, () -> service.stage(10, "新阶段", 2, List.of(3L)));
  }
}
