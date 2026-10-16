package com.xuri.training.web;

import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.service.*;
import jakarta.validation.Valid;
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
    return db.paths(Current.actor().admin(), Current.actor().id());
  }

  @PostMapping("/paths")
  public Object create(@Valid @RequestBody Requests.Path r) {
    return service.path(r.title(), r.positionId(), r.gradeId());
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
  public void assign(@PathVariable long id, @Valid @RequestBody Requests.Members r) {
    service.assign(id, r.userIds());
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
  public Object createPlan(@Valid @RequestBody Requests.Plan r) {
    return service.plan(
        r.title(),
        r.startAt().withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime(),
        r.dueAt().withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime(),
        r.courseIds(),
        r.userIds(),
        r.examIds());
  }

  @PostMapping("/plans/{id}/publish")
  public void publishPlan(@PathVariable long id) {
    service.publishPlan(id);
  }

  @GetMapping("/plans/{id}")
  public Object plan(@PathVariable long id) {
    return service.planDetail(id);
  }
}
