package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.web.ExamController;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class EssayGradingTest {
  final ExamMapper db = mock(ExamMapper.class);
  final CourseService courses = mock(CourseService.class);
  final IdentityMapper users = mock(IdentityMapper.class);
  final WarningMapper messages = mock(WarningMapper.class);
  final ObjectMapper json = new ObjectMapper();
  final ExamService service = new ExamService(
      db, courses, users, mock(ProgramService.class), mock(ProgramMapper.class), messages,
      json, Clock.fixed(Instant.ofEpochMilli(3000), ZoneOffset.UTC));
  final Map<String, Object> attempt = row(
      "id", "attempt", "exam_id", 1L, "user_id", 8L, "status", "PENDING_GRADE",
      "score", null, "passed", false, "submitted_at_ms", 2000L);
  final Map<String, Object> exam = row(
      "id", 1L, "owner_id", 7L, "paper_id", 2L, "pass_score", 40, "title", "结业考试");
  final List<Map<String, Object>> questions = new ArrayList<>();
  final List<Map<String, Object>> answers = new ArrayList<>();

  @BeforeEach
  void fixture() {
    Current.set(new Actor(7, Set.of("EMPLOYEE", "INSTRUCTOR")));
    questions.add(question(11, "SINGLE", 10, "A"));
    questions.add(question(12, "TEXT", 20, "主观题参考答案一"));
    questions.add(question(13, "TEXT", 30, "主观题参考答案二"));
    answers.add(row("paper_question_id", 11L, "answer", "A", "score", 10));
    answers.add(row("paper_question_id", 12L, "answer", "员工的回答一", "score", null));
    answers.add(row("paper_question_id", 13L, "answer", "员工的回答二", "score", null));
    when(db.lockAttempt("attempt")).thenReturn(attempt);
    when(db.attemptById("attempt")).thenReturn(attempt);
    when(db.examById(1)).thenReturn(exam);
    when(db.paperById(2)).thenReturn(row("id", 2L, "owner_id", 9L));
    when(db.paperQuestions(2)).thenReturn(questions);
    when(db.answers("attempt")).thenReturn(answers);
    when(db.gradeEssay(eq("attempt"), anyLong(), anyInt(), anyLong(), nullable(String.class)))
        .thenAnswer(invocation -> {
          Map<String, Object> answer = answer(invocation.getArgument(1));
          if (answer.get("score") != null) return 0;
          answer.put("score", invocation.getArgument(2));
          answer.put("grader_id", invocation.getArgument(3));
          answer.put("grading_feedback", invocation.getArgument(4));
          return 1;
        });
    when(db.grade(eq("attempt"), anyLong(), anyInt(), nullable(Long.class)))
        .thenAnswer(invocation -> {
          answer(invocation.getArgument(1)).put("score", invocation.getArgument(2));
          return 1;
        });
    doAnswer(invocation -> {
      attempt.putAll(invocation.getArgument(0));
      return null;
    }).when(db).result(anyMap());
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  Map<String, Object> question(long id, String type, int score, String reference) {
    return row("id", id, "score", score, "snapshot_json", service.encode(row(
        "type", type, "stem", "题目" + id, "options_json", "{}",
        "answer", reference, "explanation", "阅卷参考解析")));
  }

  Map<String, Object> answer(long id) {
    return answers.stream().filter(item -> id(item, "paper_question_id") == id)
        .findFirst().orElseThrow();
  }

  MockMvc http() {
    return MockMvcBuilders.standaloneSetup(
        new ExamController(service, db, courses, mock(ExcelService.class)))
        .setControllerAdvice(new Errors()).build();
  }

  @Test
  void partialGradingKeepsResultPendingAndDoesNotPublishScore() {
    service.grade("attempt", 12, 15, "要点基本完整");
    assertEquals(15, answer(12).get("score"));
    assertEquals("要点基本完整", answer(12).get("grading_feedback"));
    assertEquals(7L, answer(12).get("grader_id"));
    assertEquals("PENDING_GRADE", attempt.get("status"));
    assertNull(attempt.get("score"));
    assertEquals(false, attempt.get("passed"));
    verify(db, never()).result(anyMap());
    verify(messages, never()).notify(anyLong(), anyString(), anyString());
    verify(users).audit(7L, "EXAM_GRADE", "attempt", "12:15");
  }

  @Test
  void finalGradeSumsObjectiveAndEveryEssayAndPublishesPassingResult() {
    service.grade("attempt", 12, 15, "第一题评语");
    service.grade("attempt", 13, 15, "第二题评语");
    assertEquals("GRADED", attempt.get("status"));
    assertEquals(40, attempt.get("score"));
    assertEquals(true, attempt.get("passed"));
    verify(messages).notify(8L, "考试成绩已发布", "结业考试");
    assertEquals(409, assertThrows(Problem.class,
        () -> service.grade("attempt", 13, 16, "修改分数")).status);
    assertEquals(15, answer(13).get("score"));
  }

  @Test
  void repeatedGradeCannotOverwriteOneAnswerWhileOtherEssaysAreStillPending() {
    service.grade("attempt", 12, 0, null);
    assertEquals(409, assertThrows(Problem.class,
        () -> service.grade("attempt", 12, 10, null)).status);
    assertEquals(0, answer(12).get("score"));
    verify(db, times(1)).gradeEssay("attempt", 12, 0, 7L, null);
    verify(db, never()).result(anyMap());
  }

  @Test
  void outOfRangeScoresObjectiveQuestionsAndUnknownQuestionsAreRejected() {
    for (int score : List.of(-1, 21)) {
      assertEquals(400, assertThrows(Problem.class,
          () -> service.grade("attempt", 12, score, null)).status);
    }
    assertEquals(400, assertThrows(Problem.class,
        () -> service.grade("attempt", 11, 5, null)).status);
    assertEquals(400, assertThrows(Problem.class,
        () -> service.grade("attempt", 99, 5, null)).status);
    verify(db, never()).gradeEssay(anyString(), anyLong(), anyInt(), anyLong(), nullable(String.class));
  }

  @Test
  void unrelatedTeacherCannotReadReferencesOrGradeAnswers() {
    Current.set(new Actor(10, Set.of("EMPLOYEE", "INSTRUCTOR")));
    assertEquals(403, assertThrows(Problem.class,
        () -> service.gradingView("attempt")).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> service.grade("attempt", 12, 10, null)).status);
    verify(db, never()).paperQuestions(anyLong());
    verify(db, never()).answers(anyString());
  }

  @Test
  void paperOwnerCanGradeAnExamScheduledByAnotherOwner() {
    Current.set(new Actor(9, Set.of("EMPLOYEE", "INSTRUCTOR")));
    service.grade("attempt", 12, 20, "试卷负责人阅卷");
    assertEquals(9L, answer(12).get("grader_id"));
    assertEquals("PENDING_GRADE", attempt.get("status"));
  }

  @Test
  void administratorMayGradeOtherEmployeesButCannotGradeOwnAttempt() {
    Current.set(new Actor(20, Set.of("ADMIN")));
    service.grade("attempt", 12, 0, null);
    assertEquals(20L, answer(12).get("grader_id"));
    Current.set(new Actor(8, Set.of("ADMIN")));
    assertEquals(403, assertThrows(Problem.class,
        () -> service.grade("attempt", 13, 30, null)).status);
    assertEquals(403, assertThrows(Problem.class,
        () -> service.gradingView("attempt")).status);
  }

  @Test
  void gradingViewRejectsUnsubmittedAttemptBeforeReadingQuestionsOrAnswers() {
    attempt.put("status", "IN_PROGRESS");
    assertEquals(409, assertThrows(Problem.class,
        () -> service.gradingView("attempt")).status);
    assertEquals(409, assertThrows(Problem.class,
        () -> service.grade("attempt", 12, 15, null)).status);
    verify(db, never()).paperQuestions(anyLong());
    verify(db, never()).answers(anyString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void gradingViewIncludesStableQuestionAndAnswerFieldsWithoutLeakingToLearner() {
    var view = service.gradingView("attempt");
    var items = (List<Map<String, Object>>) view.get("questions");
    var essay = items.get(1);
    assertEquals(12L, essay.get("id"));
    assertEquals("TEXT", essay.get("type"));
    assertEquals(20, essay.get("max_score"));
    assertEquals("主观题参考答案一", essay.get("reference_answer"));
    assertEquals("员工的回答一", essay.get("student_answer"));
    assertNull(essay.get("earned_score"));
    Current.set(new Actor(8, Set.of("EMPLOYEE")));
    String learnerResponse = service.encode(service.attemptView("attempt"));
    assertFalse(learnerResponse.contains("主观题参考答案"));
    assertFalse(learnerResponse.contains("阅卷参考解析"));
    assertFalse(learnerResponse.contains("snapshot_json"));
  }

  @Test
  void submitRequiresManualGradingForEveryEssayIncludingBlankAnswer() {
    attempt.put("status", "IN_PROGRESS");
    answer(13).put("answer", "");
    service.finish("attempt");
    assertEquals("PENDING_GRADE", attempt.get("status"));
    assertNull(attempt.get("score"));
    assertNull(answer(12).get("score"));
    assertNull(answer(13).get("score"));
    verify(db).grade("attempt", 11, 10, null);
    verify(db, never()).grade(eq("attempt"), eq(13L), anyInt(), nullable(Long.class));
  }

  @Test
  void missingEssayAnswerCannotAccidentallyFinalizeIncompleteAttempt() {
    answers.removeIf(item -> id(item, "paper_question_id") == 13);
    service.grade("attempt", 12, 20, null);
    assertEquals("PENDING_GRADE", attempt.get("status"));
    verify(db, never()).result(anyMap());
  }

  @Test
  void gradingHttpEndpointPersistsOptionalFeedbackAndRejectsOversizedFeedback() throws Exception {
    MockMvc mvc = http();
    mvc.perform(put("/api/teaching/attempts/attempt/grade")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(row("questionId", 12, "score", 15, "feedback", "继续完善举例"))))
        .andExpect(status().isOk());
    assertEquals("继续完善举例", answer(12).get("grading_feedback"));
    mvc.perform(put("/api/teaching/attempts/attempt/grade")
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(row(
            "questionId", 13, "score", 15, "feedback", "评".repeat(1001)))))
        .andExpect(status().isBadRequest());
    assertNull(answer(13).get("score"));
  }

  @Test
  void gradingHttpReadCannotExposeReferenceAnswersBeforeSubmission() throws Exception {
    attempt.put("status", "IN_PROGRESS");
    http().perform(get("/api/teaching/attempts/attempt"))
        .andExpect(status().isConflict())
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("主观题参考答案"))));
    verify(db, never()).paperQuestions(anyLong());
  }

  @Test
  void gradingHttpEndpointRequiresExplicitScore() throws Exception {
    http().perform(put("/api/teaching/attempts/attempt/grade")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"questionId\":12}"))
        .andExpect(status().isBadRequest());
    assertNull(answer(12).get("score"));
    verify(db, never()).gradeEssay(anyString(), anyLong(), anyInt(), anyLong(), nullable(String.class));
  }
}
