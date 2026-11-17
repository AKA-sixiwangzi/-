package com.xuri.training.web;

import com.xuri.training.service.OfflineService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class OfflineController {
  private final OfflineService service;

  public OfflineController(OfflineService service) {
    this.service = service;
  }

  public record Create(
      @NotBlank @Size(max = 200) String title,
      @Positive Long planId,
      @Positive Long courseId,
      @Positive long instructorId,
      @NotNull OffsetDateTime startAt,
      @NotNull OffsetDateTime endAt,
      @NotBlank @Size(max = 200) String location,
      @NotEmpty @Size(max = 1000) List<@NotNull @Positive Long> userIds) {}

  public record Record(
      @Positive long userId,
      @NotBlank @Pattern(regexp = "PRESENT|ABSENT|EXCUSED") String status,
      @Min(0) @Max(100) Integer score,
      @Size(max = 1000) String note) {}

  public record Attendance(
      @NotEmpty @Size(max = 1000) List<@NotNull @Valid Record> records,
      @NotBlank @Size(max = 1000) String reason) {}

  @GetMapping("/offline-sessions")
  public Object list() {
    return service.list();
  }

  @GetMapping("/offline-sessions/options")
  public Object options() {
    return service.options();
  }

  @PostMapping("/offline-sessions")
  public Object create(@Valid @RequestBody Create request) {
    return service.create(request.title(), request.planId(), request.courseId(), request.instructorId(),
        request.startAt(), request.endAt(), request.location(), request.userIds());
  }

  @GetMapping("/offline-sessions/{id}")
  public Object detail(@PathVariable long id) {
    return service.detail(id);
  }

  @PutMapping("/offline-sessions/{id}/attendance")
  public void record(@PathVariable long id, @Valid @RequestBody Attendance request) {
    service.record(id, request.records().stream().map(value ->
        new OfflineService.Attendance(value.userId(), value.status(), value.score(), value.note())).toList(),
        request.reason());
  }

  @GetMapping("/me/offline-training")
  public Object personal() {
    return service.personal();
  }
}
