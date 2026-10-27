package com.xuri.training.web;

import com.xuri.training.mapper.ExamMapper;
import com.xuri.training.security.*;
import com.xuri.training.service.*;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ExamController {
  private final ExamService service;
  private final ExamMapper db;
  private final CourseService courses;
  private final ExcelService excel;

  public ExamController(
      ExamService service, ExamMapper db, CourseService courses, ExcelService excel) {
    this.service = service;
    this.db = db;
    this.courses = courses;
    this.excel = excel;
  }

  @PostMapping("/questions")
  public Object question(@Valid @RequestBody Requests.Question r) {
    return service.question(
        r.courseId(), r.type(), r.stem(), r.options(), r.answer(), r.explanation(), "MANUAL");
  }

  @GetMapping("/courses/{id}/questions")
  public Object questions(@PathVariable long id) {
    courses.manage(id);
    return db.questions(id);
  }

  @PostMapping("/questions/{id}/approve")
  public void approve(@PathVariable long id) {
    service.approveQuestion(id);
  }

  @PostMapping("/papers")
  public Object paper(@Valid @RequestBody Requests.Paper r) {
    return service.paper(r.title(), r.questionIds(), r.scores());
  }

  @GetMapping("/papers")
  public Object papers() {
    return db.papers(Current.actor().id(), Current.actor().admin());
  }

  @GetMapping("/papers/{id}")
  public Object paperDetail(@PathVariable long id) {
    return service.paperDetail(id);
  }

  @PostMapping("/papers/{id}/publish")
  public void publish(@PathVariable long id) {
    service.publish(id);
  }

  @GetMapping("/papers/import-template")
  public ResponseEntity<byte[]> template() throws IOException {
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=paper-template.xlsx")
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(excel.template());
  }

  @PostMapping("/papers/import")
  public Object importPaper(@RequestParam String title, @RequestParam MultipartFile file)
      throws IOException {
    com.xuri.training.common.Problem.require(
        !title.isBlank() && title.length() <= 200, 400, "试卷名称不合法");
    return excel.importPaper(title, file);
  }

  @PostMapping("/exams")
  public Object exam(@Valid @RequestBody Requests.Exam r) {
    return service.exam(
        r.title(),
        r.paperId(),
        r.stageId(),
        r.startAt().withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime(),
        r.endAt().withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime(),
        r.durationMinutes(),
        r.passScore(),
        r.maxAttempts(),
        r.prerequisiteCourseIds(),
        r.userIds());
  }

  @GetMapping("/exams")
  public Object center() {
    return service.center();
  }

  @PostMapping("/exams/{id}/start")
  public Object start(@PathVariable long id) {
    return service.start(id);
  }

  @GetMapping("/attempts/{id}")
  public Object attempt(@PathVariable String id) {
    return service.attemptView(id);
  }

  @PutMapping("/attempts/{id}/answers")
  public void answer(@PathVariable String id, @Valid @RequestBody Requests.Answer r) {
    service.save(id, r.questionId(), r.answer());
  }

  @PostMapping("/attempts/{id}/submit")
  public Object submit(@PathVariable String id) {
    return service.submit(id);
  }

  @PostMapping("/attempts/{id}/events")
  public void event(@PathVariable String id, @Valid @RequestBody Requests.ExamEvent r) {
    service.riskEvent(id, r.event());
  }

  @GetMapping("/teaching/grading")
  public Object pending() {
    return db.pending(Current.actor().id(), Current.actor().admin());
  }

  @GetMapping("/teaching/exams")
  public Object managedExams() {
    return db.managed(Current.actor().id(), Current.actor().admin());
  }

  @GetMapping("/teaching/attempts/{id}")
  public Object grading(@PathVariable String id) {
    return service.gradingView(id);
  }

  @PutMapping("/teaching/attempts/{id}/grade")
  public void grade(@PathVariable String id, @Valid @RequestBody Requests.Grade r) {
    service.grade(id, r.questionId(), r.score());
  }

  @GetMapping("/learning/wrong-answers")
  public Object wrong() {
    return db.wrongAnswers(Current.actor().id());
  }
}
