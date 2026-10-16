package com.xuri.training.web;

import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.service.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OperationsController {
  private final WarningService warnings;
  private final WarningMapper db;
  private final ResultService results;
  private final ResultMapper resultDb;
  private final CourseService courses;
  private final AiService ai;
  private final LearningService learning;

  public OperationsController(
      WarningService warnings,
      WarningMapper db,
      ResultService results,
      ResultMapper resultDb,
      CourseService courses,
      AiService ai,
      LearningService learning) {
    this.warnings = warnings;
    this.db = db;
    this.results = results;
    this.resultDb = resultDb;
    this.courses = courses;
    this.ai = ai;
    this.learning = learning;
  }

  @GetMapping("/warnings")
  public Object warnings() {
    return db.list(Current.actor().admin(), Current.actor().id());
  }

  @GetMapping("/warnings/{id}")
  public Object detail(@PathVariable long id) {
    return warnings.detail(id);
  }

  @PostMapping("/warnings/{id}/actions")
  public void action(@PathVariable long id, @Valid @RequestBody Requests.WarningAction r) {
    warnings.action(id, r.action(), r.note());
  }

  @PostMapping("/warnings/{id}/invalidate-session")
  public void invalidate(@PathVariable long id, @Valid @RequestBody Requests.WarningAction r) {
    learning.invalidate(id, r.note());
  }

  @GetMapping("/notifications")
  public Object notifications() {
    return db.notifications(Current.actor().id());
  }

  @PostMapping("/notifications/{id}/read")
  public void read(@PathVariable long id) {
    com.xuri.training.common.Problem.require(db.read(id, Current.actor().id()) == 1, 404, "通知不存在");
  }

  @GetMapping("/dashboards/company")
  public Object company() {
    return results.company();
  }

  @GetMapping("/dashboards/company/details")
  public Object companyDetails(@RequestParam(defaultValue = "0") int offset) {
    results.company();
    com.xuri.training.common.Problem.require(offset >= 0, 400, "偏移量不合法");
    Actor a = Current.actor();
    return resultDb.companyDetails(a.admin(), a.id(), offset);
  }

  @GetMapping("/reports/training.csv")
  public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
      export() {
    results.company();
    Actor a = Current.actor();
    org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody stream =
        output -> {
          output.write("\uFEFF员工ID,姓名,部门ID,课程ID,课程,完成状态\r\n".getBytes(StandardCharsets.UTF_8));
          for (int offset = 0; ; offset += 200) {
            var page = resultDb.companyDetails(a.admin(), a.id(), offset);
            if (page.isEmpty()) break;
            for (var r : page) {
              StringBuilder line = new StringBuilder();
              for (String k :
                  List.of(
                      "id", "display_name", "department_id", "course_id", "title", "completed")) {
                String v = Objects.toString(r.get(k), "");
                if (v.stripLeading().matches("^[=+@\\-].*")) v = "'" + v;
                line.append('"').append(v.replace("\"", "\"\"")).append("\",");
              }
              line.setLength(line.length() - 1);
              line.append("\r\n");
              output.write(line.toString().getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
            if (page.size() < 200) break;
          }
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .header("Content-Disposition", "attachment; filename=training-report.csv")
        .body(stream);
  }

  @GetMapping("/courses/{id}/coursework")
  public Object coursework(@PathVariable long id) {
    return results.coursework(id);
  }

  @PostMapping("/courses/{id}/assignments")
  public Object assignment(@PathVariable long id, @Valid @RequestBody Requests.Assignment r) {
    return results.assignment(id, r.title(), r.instructions());
  }

  @PostMapping("/assignments/{id}/submissions")
  public void submission(@PathVariable long id, @Valid @RequestBody Requests.Submission r) {
    results.submit(id, r.content(), r.assetId());
  }

  @PutMapping("/submissions/{id}/grade")
  public void grade(@PathVariable long id, @Valid @RequestBody Requests.AssignmentGrade r) {
    results.grade(id, r.score(), r.feedback());
  }

  @PostMapping("/courses/{id}/feedback")
  public void feedback(@PathVariable long id, @Valid @RequestBody Requests.Feedback r) {
    results.feedback(id, r.rating(), r.comment());
  }

  @GetMapping("/courses/{id}/feedback")
  public Object feedbacks(@PathVariable long id) {
    courses.manage(id);
    return resultDb.feedbackList(id);
  }

  @PostMapping("/courses/{id}/ai/ask")
  public Object ask(@PathVariable long id, @Valid @RequestBody Requests.Ask r) {
    return ai.ask(id, r.question());
  }

  @PostMapping("/courses/{id}/ai/question-drafts")
  public Object draft(@PathVariable long id, @Valid @RequestBody Requests.Draft r) {
    return ai.draft(id, r.count());
  }
}
