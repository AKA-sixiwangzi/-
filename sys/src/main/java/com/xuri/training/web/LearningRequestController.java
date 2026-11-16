package com.xuri.training.web;

import com.xuri.training.service.LearningRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class LearningRequestController {
  private final LearningRequestService service;

  public LearningRequestController(LearningRequestService service) {
    this.service = service;
  }

  public record Create(
      @Positive Long pathId,
      @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> courseIds,
      @NotEmpty @Size(max = 1000) List<@NotNull @Positive Long> userIds,
      @NotBlank @Size(max = 1000) String reason) {}

  public record Review(@NotNull Boolean approve, @NotBlank @Size(max = 1000) String note) {}

  public record Grant(
      @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> courseIds,
      @NotEmpty @Size(max = 1000) List<@NotNull @Positive Long> userIds,
      @NotBlank @Size(max = 1000) String reason) {}

  @GetMapping("/learning-requests")
  public Object list() {
    return service.list();
  }

  @GetMapping("/learning-requests/options")
  public Object options() {
    return service.options();
  }

  @PostMapping("/learning-requests")
  public Object create(@Valid @RequestBody Create request) {
    return service.create(request.pathId(), request.courseIds(), request.userIds(), request.reason());
  }

  @PostMapping("/learning-requests/{id}/review")
  public void review(@PathVariable long id, @Valid @RequestBody Review request) {
    service.review(id, request.approve(), request.note());
  }

  @PostMapping("/admin/course-grants")
  public Object grant(@Valid @RequestBody Grant request) {
    return service.directGrant(request.courseIds(), request.userIds(), request.reason());
  }
}
