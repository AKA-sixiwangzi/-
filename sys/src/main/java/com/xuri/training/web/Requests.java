package com.xuri.training.web;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.*;

public final class Requests {
  private Requests() {}

  public record Login(
      @NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 72) String password) {}

  public record User(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_.@-]{3,80}") String username,
      @NotBlank @Size(min = 12, max = 72) String password,
      @NotBlank @Size(max = 100) String displayName,
      Long departmentId,
      Long positionId,
      Long gradeId) {}

  public record Password(
      @NotBlank String oldPassword, @NotBlank @Size(min = 12, max = 72) String newPassword) {}

  public record Role(@NotBlank String role, boolean enabled) {}

  public record Active(boolean active) {}

  public record UserProfile(
      @NotBlank @Size(max = 100) String displayName,
      Long departmentId,
      Long positionId,
      Long gradeId) {}

  public record Name(
      @NotBlank @Size(max = 100) String name, Long parentId, @Min(0) int sortOrder) {}

  public record Scope(@Positive long departmentId) {}

  public record Course(
      @NotBlank @Size(max = 200) String title,
      @NotNull @Size(max = 3000) String description,
      @NotBlank @Size(max = 100) String category) {}

  public record Lesson(
      @NotBlank @Size(max = 200) String title,
      @Min(1) int sortOrder,
      @Min(1) @Max(86400) int durationSeconds,
      @NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String videoAssetId,
      @DecimalMin("0.5") @DecimalMax("1.0") double coverageThreshold,
      @DecimalMin("0.5") @DecimalMax("1.0") double timeThreshold) {}

  public record Material(
      @NotBlank @Size(max = 200) String title,
      @NotNull @Size(max = 200000) String content,
      @Pattern(regexp = "[0-9a-fA-F-]{36}") String assetId) {}

  public record Review(boolean approve, @NotBlank @Size(max = 1000) String note) {}

  public record Heartbeat(
      @Min(1) int seq,
      @DecimalMin("0") double position,
      @NotBlank String event,
      boolean visible,
      @DecimalMin("0.1") @DecimalMax("4.0") double rate) {}

  public record WarningAction(
      @NotBlank String action, @NotBlank @Size(min = 5, max = 1800) String note) {}

  public record Path(@NotBlank @Size(max = 200) String title, Long positionId, Long gradeId) {}

  public record Stage(
      @NotBlank @Size(max = 200) String title,
      @Min(1) int sortOrder,
      @NotEmpty @Size(max = 100) List<@Positive Long> courseIds) {}

  public record Members(@NotEmpty @Size(max = 1000) List<@Positive Long> userIds) {}

  public record Plan(
      @NotBlank @Size(max = 200) String title,
      @NotNull OffsetDateTime startAt,
      @NotNull OffsetDateTime dueAt,
      @NotEmpty @Size(max = 100) List<@Positive Long> courseIds,
      @NotEmpty @Size(max = 1000) List<@Positive Long> userIds,
      @NotNull @Size(max = 100) List<@Positive Long> examIds) {}

  public record Question(
      @Positive long courseId,
      @NotBlank String type,
      @NotBlank @Size(max = 4000) String stem,
      @NotNull Map<String, String> options,
      @NotBlank @Size(max = 2000) String answer,
      @NotNull @Size(max = 4000) String explanation) {}

  public record Paper(
      @NotBlank @Size(max = 200) String title,
      @NotEmpty @Size(max = 200) List<@Positive Long> questionIds,
      @NotEmpty @Size(max = 200) List<@Min(1) @Max(100) Integer> scores) {}

  public record Exam(
      @NotBlank @Size(max = 200) String title,
      @Positive long paperId,
      Long stageId,
      @NotNull OffsetDateTime startAt,
      @NotNull OffsetDateTime endAt,
      @Min(1) @Max(480) int durationMinutes,
      @Min(1) int passScore,
      @Min(1) @Max(10) int maxAttempts,
      @NotEmpty @Size(max = 100) List<@Positive Long> prerequisiteCourseIds,
      @NotEmpty @Size(max = 1000) List<@Positive Long> userIds) {}

  public record Answer(@Positive long questionId, @NotNull @Size(max = 4000) String answer) {}

  public record Grade(@Positive long questionId, @Min(0) int score) {}

  public record ExamEvent(@NotBlank @Pattern(regexp = "BLUR|FOCUS") String event) {}

  public record Ask(@NotBlank @Size(max = 1000) String question) {}

  public record Draft(@Min(1) @Max(10) int count) {}

  public record Assignment(
      @NotBlank @Size(max = 200) String title, @NotBlank @Size(max = 3000) String instructions) {}

  public record Submission(
      @NotBlank @Size(max = 4000) String content,
      @Pattern(regexp = "[0-9a-fA-F-]{36}") String assetId) {}

  public record AssignmentGrade(
      @Min(0) @Max(100) int score, @NotBlank @Size(max = 2000) String feedback) {}

  public record Feedback(@Min(1) @Max(5) int rating, @NotNull @Size(max = 2000) String comment) {}
}
