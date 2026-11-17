package com.xuri.training.web;

import com.xuri.training.service.PlanOperationsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PlanOperationsController {
  private final PlanOperationsService service;

  public PlanOperationsController(PlanOperationsService service) {
    this.service = service;
  }

  public record ReminderRules(@Positive Long responsibleUserId,
      @NotNull @Min(0) @Max(8760) Integer remindBeforeHours,
      @NotNull @Min(1) @Max(720) Integer remindEveryHours,
      @NotNull Boolean pushResults) {}

  @PutMapping("/plans/{id}/reminder-rules")
  public void rules(@PathVariable long id, @Valid @RequestBody ReminderRules rules) {
    service.rules(id, rules.responsibleUserId(), rules.remindBeforeHours(),
        rules.remindEveryHours(), rules.pushResults());
  }

  @PostMapping("/plans/{id}/remind")
  public Object remind(@PathVariable long id) {
    return service.remind(id);
  }

  @GetMapping("/teaching/plans")
  public Object plans() {
    return service.teachingPlans();
  }

  @GetMapping("/teaching/plans/{id}")
  public Object detail(@PathVariable long id) {
    return service.teachingPlan(id);
  }
}
