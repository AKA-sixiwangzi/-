package com.xuri.training.web;

import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProgramController {
  private final ProgramService service;
  private final ProgramMapper db;

  public ProgramController(ProgramService service, ProgramMapper db) {
    this.service = service;
    this.db = db;
  }

  @GetMapping("/paths")
  public Object paths() {
    return service.paths();
  }

  @PostMapping("/paths")
  public Object create(@Valid @RequestBody Requests.Path r) {
    return service.path(
        r.title(), r.positionId(), r.gradeId(), r.allDepartments(), r.departmentIds());
  }

  @GetMapping("/paths/{id}")
  public Object detail(@PathVariable long id) {
    return service.detail(id, Current.actor().id());
  }

  @PostMapping("/paths/{id}/stages")
  public Object stage(@PathVariable long id, @Valid @RequestBody Requests.Stage r) {
    return service.stage(id, r.title(), r.sortOrder(), r.courseIds());
  }

  @PostMapping("/paths/{id}/publish")
  public void publish(@PathVariable long id) {
    service.publish(id);
  }

  @PostMapping("/paths/{id}/assign")
  public void assign(@PathVariable long id, @Valid @RequestBody Requests.RouteMembers r) {
    service.assign(id, r.userIds(), r.departmentId(), r.positionId());
  }

  @PostMapping("/paths/{id}/certificate")
  public void certificate(@PathVariable long id) {
    service.certificate(id);
  }

  @GetMapping("/certificates")
  public Object certificates() {
    return db.certificates(Current.actor().id());
  }

  @GetMapping("/plans")
  public Object plans() {
    return db.plans(Current.actor().admin(), Current.actor().id());
  }

  @PostMapping("/plans")
  public Object createPlan(@Valid @RequestBody PlanCreate r) {
    return service.plan(
        r.title(),
        r.startAt().withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime(),
        r.dueAt().withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime(),
        r.courseIds(),
        r.userIds(),
        r.examIds(), r.responsibleUserId(), r.remindBeforeHours() == null ? 72 : r.remindBeforeHours(),
        r.remindEveryHours() == null ? 24 : r.remindEveryHours(), r.pushResults() == null || r.pushResults());
  }

  public record PlanCreate(
      @NotBlank @Size(max = 200) String title,
      @NotNull OffsetDateTime startAt, @NotNull OffsetDateTime dueAt,
      @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> courseIds,
      @NotEmpty @Size(max = 1000) List<@NotNull @Positive Long> userIds,
      @NotNull @Size(max = 100) List<@NotNull @Positive Long> examIds,
      @Positive Long responsibleUserId,
      @Min(0) @Max(8760) Integer remindBeforeHours,
      @Min(1) @Max(720) Integer remindEveryHours, Boolean pushResults) {}

  @PostMapping("/plans/{id}/publish")
  public void publishPlan(@PathVariable long id) {
    service.publishPlan(id);
  }

  @GetMapping("/plans/{id}")
  public Object plan(@PathVariable long id) {
    return service.planDetail(id);
  }
}
