package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuri.training.common.Problem;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.web.Requests;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class CourseMaterialsTest {
  static final String PDF = "11111111-1111-1111-1111-111111111111";
  static final String PPT = "22222222-2222-2222-2222-222222222222";
  static final String PPTX = "33333333-3333-3333-3333-333333333333";
  CourseMapper db = mock(CourseMapper.class);
  IdentityMapper users = mock(IdentityMapper.class);
  CourseService service = new CourseService(db, users);

  @BeforeEach
  void setup() {
    Current.set(new Actor(7, Set.of("EMPLOYEE")));
    Map<String, Object> course = row("id", 12L, "owner_id", 7L, "status", "DRAFT");
    when(db.lockCourse(12)).thenReturn(course);
    when(db.course(12)).thenReturn(course);
    when(db.asset(PDF)).thenReturn(row("owner_id", 7L, "content_type", "application/pdf"));
    when(db.asset(PPT)).thenReturn(row("owner_id", 7L, "content_type", "application/vnd.ms-powerpoint"));
    when(db.asset(PPTX)).thenReturn(row("owner_id", 7L, "content_type",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
  }

  @AfterEach
  void clear() {
    Current.clear();
  }

  private Map<String, Object> textMaterial(boolean required, boolean acknowledged) {
    return row("id", 20L, "course_id", 12L, "material_type", "TEXT", "content", "岗位操作规范",
        "required", required, "acknowledged", acknowledged);
  }

  private void publishedAndAssigned() {
    when(db.course(12)).thenReturn(row("id", 12L, "owner_id", 8L, "status", "PUBLISHED"));
    when(db.enrolled(7, 12)).thenReturn(1);
  }

  @Test
  void requiredTextCourseCanBeSubmittedWithoutAnyVideo() {
    when(db.materials(12)).thenReturn(List.of(textMaterial(true, false)));
    service.submit(12);
    verify(db).status(12, "PENDING", null, "");
  }

  @Test
  void requiredPdfCourseCanBeSubmittedWithoutAnyVideo() {
    when(db.materials(12)).thenReturn(List.of(
        row("id", 20L, "material_type", "PDF", "content", "", "asset_id", PDF, "required", true)));
    service.submit(12);
    verify(db).status(12, "PENDING", null, "");
  }

  @Test
  void emptyCourseOrOnlyOptionalDocumentsCannotBeSubmitted() {
    assertEquals(400, assertThrows(Problem.class, () -> service.submit(12)).status);
    when(db.materials(12)).thenReturn(List.of(textMaterial(false, false)));
    assertEquals(400, assertThrows(Problem.class, () -> service.submit(12)).status);
    verify(db, never()).status(anyLong(), anyString(), any(), anyString());
  }

  @Test
  void requiredDocumentDoesNotHideAnInvalidVideoChapter() {
    when(db.materials(12)).thenReturn(List.of(textMaterial(true, false)));
    when(db.lessons(12)).thenReturn(List.of(row("id", 3L, "video_asset_id", null)));
    assertEquals(400, assertThrows(Problem.class, () -> service.submit(12)).status);
    verify(db, never()).status(anyLong(), anyString(), any(), anyString());
  }

  @Test
  void pdfAndBothPowerpointFormatsUseTheirActualAssetTypes() {
    assertEquals("PDF", service.material(12, "PDF讲义", null, PDF, "PDF", null, true).get("material_type"));
    assertEquals("PPT", service.material(12, "PPT课件", null, PPT, "PPT", null, true).get("material_type"));
    assertEquals("PPT", service.material(12, "PPTX课件", null, PPTX, "PPT", null, true).get("material_type"));
    assertEquals("PDF", service.material(12, "旧PDF附件", "", PDF).get("material_type"));
  }

  @Test
  void textMustHaveARealBodyOrTextFileAndCannotMasqueradeAsPdf() {
    assertEquals(400, assertThrows(Problem.class,
        () -> service.material(12, "空讲义", "  ", null, "TEXT", null, true)).status);
    assertEquals(400, assertThrows(Problem.class,
        () -> service.material(12, "错误类型", "正文", PDF, "TEXT", null, true)).status);
    assertEquals(400, assertThrows(Problem.class,
        () -> service.material(12, "伪PDF", "正文", PPT, "PDF", null, true)).status);
    assertEquals(400, assertThrows(Problem.class,
        () -> service.material(12, "无附件", "正文", null, "PPT", null, true)).status);
    verify(db, never()).materialCreate(anyMap());
  }

  @Test
  void ownedTextFileCanBeRequiredWithoutDuplicatingItsBody() {
    String text = "44444444-4444-4444-4444-444444444444";
    when(db.asset(text)).thenReturn(row("owner_id", 7L, "content_type", "text/plain"));
    Map<String, Object> result = service.material(12, "文字附件", "", text, "TEXT", null, true);
    assertEquals(text, result.get("asset_id"));
    assertEquals("", result.get("content"));
    assertEquals(true, result.get("required"));
    verify(db).materialCreate(result);
  }

  @Test
  void onlyHttpLinksWithHostAndWithoutCredentialsAreAccepted() {
    for (String link : List.of("https://example.com/培训?x=1", "http://intranet:8080/docs")) {
      assertEquals(link, service.material(12, "学习链接", null, null, "LINK", link, true).get("external_url"));
    }
    for (String link : List.of("javascript:alert(1)", "data:text/html,unsafe", "file:///etc/passwd",
        "//example.com/path", "https:///missing", "https://account:password@example.com/",
        "https://example.com:99999/doc", "https://example.com/has space")) {
      assertEquals(400, assertThrows(Problem.class,
          () -> service.material(12, "错误链接", null, null, "LINK", link, true)).status, link);
    }
  }

  @Test
  void linksCannotBeSmuggledIntoFilesAndFileLinksCannotBeMixed() {
    assertEquals(400, assertThrows(Problem.class,
        () -> service.material(12, "附件", "", PDF, "PDF", "https://example.com/", true)).status);
    assertEquals(400, assertThrows(Problem.class,
        () -> service.material(12, "链接", "", PDF, "LINK", "https://example.com/", true)).status);
    verify(db, never()).materialCreate(anyMap());
  }

  @Test
  void employeeCannotAttachSomebodyElsesFile() {
    when(db.asset(PDF)).thenReturn(row("owner_id", 8L, "content_type", "application/pdf"));
    assertEquals(403, assertThrows(Problem.class,
        () -> service.material(12, "他人PDF", "", PDF, "PDF", null, true)).status);
    verify(db, never()).materialCreate(anyMap());
  }

  @Test
  void invalidStoredRequiredLinkPreventsReviewSubmission() {
    when(db.materials(12)).thenReturn(List.of(
        row("required", true, "material_type", "LINK", "external_url", "javascript:alert(1)")));
    assertEquals(400, assertThrows(Problem.class, () -> service.submit(12)).status);
    verify(db, never()).status(anyLong(), anyString(), any(), anyString());
  }

  @Test
  void oldMaterialRequestWithoutNewFieldsStillReadsAsOptional() throws Exception {
    Requests.Material input = new ObjectMapper().readValue(
        "{\"title\":\"旧讲义\",\"content\":\"保留正文\"}", Requests.Material.class);
    Map<String, Object> result = service.material(12, input.title(), input.content(), input.assetId(),
        input.materialType(), input.externalUrl(), input.required());
    assertEquals("TEXT", result.get("material_type"));
    assertEquals(false, result.get("required"));
  }

  @Test
  void openingLearningStatusDoesNotAcknowledgeOrCompleteTheCourse() {
    publishedAndAssigned();
    when(db.learningMaterials(7, 12)).thenReturn(List.of(textMaterial(true, false)));
    Map<String, Object> status = service.learningStatus(12);
    assertEquals(false, status.get("completed"));
    assertEquals(0L, status.get("videoTotal"));
    assertEquals(1L, status.get("materialTotal"));
    assertEquals(0d, status.get("percent"));
    verify(db, never()).acknowledge(anyLong(), anyLong());
    verify(db, never()).completion(anyLong(), anyLong(), anyBoolean());
  }

  @Test
  void documentOnlyCourseCompletesAfterExplicitConfirmationAndRepeatedConfirmationIsStable() {
    publishedAndAssigned();
    Map<String, Object> material = textMaterial(true, false);
    when(db.material(20)).thenReturn(material);
    when(db.learningMaterials(7, 12)).thenReturn(List.of(material));
    doAnswer(call -> { material.put("acknowledged", true); return null; })
        .when(db).acknowledge(7, 20);
    Map<String, Object> first = service.acknowledgeMaterial(20);
    Map<String, Object> repeated = service.acknowledgeMaterial(20);
    assertEquals(true, first.get("completed"));
    assertEquals(100d, first.get("percent"));
    assertEquals(1L, first.get("materialCompleted"));
    assertEquals(first, repeated);
    verify(db, times(2)).completion(7, 12, true);
    verify(users, times(2)).lockUser(7);
  }

  @Test
  void optionalMaterialNeitherBlocksNorCreatesCompletionForAnEmptyCourse() {
    publishedAndAssigned();
    when(db.learningMaterials(7, 12)).thenReturn(List.of(textMaterial(false, false)));
    assertEquals(false, service.learningStatus(12).get("completed"));
    when(db.learningVideos(7, 12)).thenReturn(List.of(row("completed", 1)));
    Map<String, Object> status = service.learningStatus(12);
    assertEquals(true, status.get("completed"));
    assertEquals(0L, status.get("materialTotal"));
    assertEquals(100d, status.get("percent"));
  }

  @Test
  void mixedCourseRequiresBothVideoCoverageAndRequiredMaterialConfirmation() {
    publishedAndAssigned();
    Map<String, Object> material = textMaterial(true, false);
    when(db.learningMaterials(7, 12)).thenReturn(List.of(material));
    when(db.learningVideos(7, 12)).thenReturn(List.of(row("completed", 1)));
    assertEquals(false, service.learningStatus(12).get("completed"));
    assertEquals(50d, service.learningStatus(12).get("percent"));
    material.put("acknowledged", true);
    when(db.learningVideos(7, 12)).thenReturn(List.of(row("completed", 0)));
    assertEquals(false, service.learningStatus(12).get("completed"));
    when(db.learningVideos(7, 12)).thenReturn(List.of(row("completed", 1)));
    assertEquals(true, service.learningStatus(12).get("completed"));
  }

  @Test
  void onlyAssignedEmployeesMayConfirmPublishedMaterials() {
    when(db.material(20)).thenReturn(textMaterial(true, false));
    assertEquals(403, assertThrows(Problem.class, () -> service.acknowledgeMaterial(20)).status);
    when(db.enrolled(7, 12)).thenReturn(1);
    assertEquals(409, assertThrows(Problem.class, () -> service.acknowledgeMaterial(20)).status);
    verify(db, never()).acknowledge(anyLong(), anyLong());
    verify(db, never()).completion(anyLong(), anyLong(), anyBoolean());
  }

  @Test
  void unassignedEmployeeCannotReadLearningStatusOfAnotherCourse() {
    when(db.course(12)).thenReturn(row("id", 12L, "owner_id", 8L, "status", "PUBLISHED"));
    assertEquals(403, assertThrows(Problem.class, () -> service.learningStatus(12)).status);
    verify(db, never()).learningMaterials(anyLong(), anyLong());
  }

  @Test
  void completingVideoPlaybackStillChecksRequiredDocumentGate() {
    LearningMapper playback = mock(LearningMapper.class);
    LearningService learning = new LearningService(playback, users, db, service,
        mock(WarningService.class), mock(WarningMapper.class), Clock.systemUTC());
    when(db.lesson(3)).thenReturn(row("id", 3L, "course_id", 12L, "duration_seconds", 100,
        "coverage_threshold", .95, "time_threshold", .95));
    when(playback.segments(7, 3)).thenReturn(List.of(row("start_seconds", 0d, "end_seconds", 100d)));
    when(db.learningVideos(7, 12)).thenReturn(List.of(row("completed", 1)));
    Map<String, Object> material = textMaterial(true, false);
    when(db.learningMaterials(7, 12)).thenReturn(List.of(material));
    learning.recalculate(7, 3);
    verify(db).completion(7, 12, false);
    material.put("acknowledged", true);
    learning.recalculate(7, 3);
    verify(db).completion(7, 12, true);
    verify(playback, times(2)).progress(argThat(p -> p.get("done").equals(true)));
  }

  @Test
  void documentConfirmationUnlocksExistingCoursePrerequisiteExam() {
    publishedAndAssigned();
    AtomicBoolean courseComplete = new AtomicBoolean(false);
    Map<String, Object> material = textMaterial(true, false);
    when(db.material(20)).thenReturn(material);
    when(db.learningMaterials(7, 12)).thenReturn(List.of(material));
    doAnswer(call -> { material.put("acknowledged", true); return null; })
        .when(db).acknowledge(7, 20);
    doAnswer(call -> { courseComplete.set(call.getArgument(2)); return null; })
        .when(db).completion(eq(7L), eq(12L), anyBoolean());
    ExamMapper exams = mock(ExamMapper.class);
    when(exams.myExams(7)).thenReturn(List.of(row("id", 9L, "max_attempts", 2)));
    when(exams.prerequisites(9, 7)).thenAnswer(call -> List.of(
        row("id", 12L, "status", "PUBLISHED", "completed", courseComplete.get() ? 1 : 0)));
    ExamService examService = new ExamService(exams, service, users, mock(ProgramService.class),
        mock(ProgramMapper.class), mock(WarningMapper.class), new ObjectMapper(), Clock.systemUTC());
    assertEquals("LEARNING_REQUIRED", examService.center().get(0).get("zone"));
    service.acknowledgeMaterial(20);
    assertEquals("READY", examService.center().get(0).get("zone"));
  }
}
