package com.xuri.training.web;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.service.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CourseController {
  private final CourseService courses;
  private final CourseMapper db;
  private final LearningService learning;
  private final LearningMapper progress;
  private final ResultMapper results;

  public CourseController(
      CourseService courses,
      CourseMapper db,
      LearningService learning,
      LearningMapper progress,
      ResultMapper results) {
    this.courses = courses;
    this.db = db;
    this.learning = learning;
    this.progress = progress;
    this.results = results;
  }

  @GetMapping("/courses")
  public Object catalog(
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String category) {
    Problem.require(
        offset >= 0 && search.length() <= 100 && category.length() <= 100, 400, "筛选条件不合法");
    return db.catalog(offset, Current.actor().admin(), Current.actor().id(), search, category);
  }

  @GetMapping("/courses/{id}")
  public Object detail(@PathVariable long id) {
    return courses.detail(id);
  }

  @GetMapping("/teaching/courses")
  public Object owned() {
    return db.owned(Current.actor().id());
  }

  @PostMapping("/courses")
  public Object create(@Valid @RequestBody Requests.Course r) {
    return courses.create(r.title(), r.description(), r.category());
  }

  @PutMapping("/courses/{id}")
  public void edit(@PathVariable long id, @Valid @RequestBody Requests.Course r) {
    courses.update(id, r.title(), r.description(), r.category());
  }

  @PostMapping("/courses/{id}/lessons")
  public Object lesson(@PathVariable long id, @Valid @RequestBody Requests.Lesson r) {
    return courses.lesson(
        id,
        r.title(),
        r.sortOrder(),
        r.durationSeconds(),
        r.videoAssetId(),
        r.coverageThreshold(),
        r.timeThreshold());
  }

  @PostMapping("/courses/{id}/materials")
  public Object material(@PathVariable long id, @Valid @RequestBody Requests.Material r) {
    return courses.material(id, r.title(), r.content(), r.assetId());
  }

  @PostMapping("/courses/{id}/submit-review")
  public void submit(@PathVariable long id) {
    courses.submit(id);
  }

  @PostMapping("/courses/{id}/archive")
  public void archive(@PathVariable long id) {
    courses.archive(id);
  }

  @PostMapping("/admin/courses/{id}/restore")
  public void restore(@PathVariable long id) {
    courses.restore(id);
  }

  @GetMapping("/admin/courses/pending")
  public Object pending() {
    Current.actor().requireAdmin();
    return db.pending();
  }

  @PostMapping("/admin/courses/{id}/review")
  public void review(@PathVariable long id, @Valid @RequestBody Requests.Review r) {
    courses.review(id, r.approve(), r.note());
  }

  @GetMapping("/courses/{id}/reviews")
  public Object reviews(@PathVariable long id) {
    courses.manage(id);
    return db.reviews(id);
  }

  @PostMapping("/courses/{id}/enroll")
  public void enroll(@PathVariable long id) {
    courses.enroll(id);
  }

  @GetMapping("/learning/courses")
  public Object myLearning() {
    return db.myLearning(Current.actor().id());
  }

  @GetMapping("/courses/{id}/progress")
  public Object myProgress(@PathVariable long id) {
    courses.enrolled(Current.actor().id(), id);
    return progress.progressList(Current.actor().id(), id);
  }

  @GetMapping("/courses/{id}/learners")
  public Object learners(@PathVariable long id) {
    courses.manage(id);
    return db.learners(id);
  }

  @PostMapping("/lessons/{id}/sessions")
  public ResponseEntity<?> start(@PathVariable long id) {
    var r = learning.start(id);
    return ResponseEntity.status(r.containsKey("conflict") ? 409 : 200).body(r);
  }

  @PostMapping("/learning/sessions/{id}/events")
  public Object event(@PathVariable String id, @Valid @RequestBody Requests.Heartbeat r) {
    return learning.heartbeat(id, r.seq(), r.position(), r.event(), r.visible(), r.rate());
  }

  @PostMapping("/learning/stop")
  public void stop() {
    learning.stop();
  }

  @GetMapping("/dashboards/personal")
  public Object personal() {
    return row(
        "summary",
        results.personal(Current.actor().id()),
        "courses",
        db.myLearning(Current.actor().id()));
  }

  @GetMapping("/dashboards/teaching")
  public Object teaching() {
    return results.teaching(Current.actor().id());
  }
}
