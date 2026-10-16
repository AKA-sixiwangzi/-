package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResultService {
  private final ResultMapper db;
  private final CourseMapper courses;
  private final CourseService access;
  private final IdentityMapper users;

  public ResultService(
      ResultMapper db, CourseMapper courses, CourseService access, IdentityMapper users) {
    this.db = db;
    this.courses = courses;
    this.access = access;
    this.users = users;
  }

  public Map<String, Object> company() {
    Actor a = Current.actor();
    Problem.require(a.admin() || a.roles().contains("MANAGER"), 403, "无权查看组织看板");
    return row(
        "summary",
        db.company(a.admin(), a.id()),
        "departments",
        db.departments(a.admin(), a.id()),
        "details",
        db.companyDetails(a.admin(), a.id(), 0),
        "plans",
        a.admin() ? db.planSummary() : List.of(),
        "updatedAt",
        java.time.Instant.now());
  }

  @Transactional
  public Map<String, Object> assignment(long course, String title, String instructions) {
    access.manage(course);
    Map<String, Object> r = row("course_id", course, "title", title, "instructions", instructions);
    db.assignment(r);
    return r;
  }

  @Transactional
  public void submit(long assignment, String content, String asset) {
    Map<String, Object> a = found(db.assignmentById(assignment));
    long user = Current.actor().id();
    access.enrolled(user, id(a, "course_id"));
    access.assetOwned(asset);
    db.submitAssignment(
        row("assignment_id", assignment, "user_id", user, "content", content, "asset_id", asset));
  }

  @Transactional
  public void grade(long id, int score, String feedback) {
    Map<String, Object> s = found(db.submission(id));
    access.manage(id(s, "course_id"));
    Problem.require(id(s, "user_id") != Current.actor().id(), 403, "不能评价自己的作业");
    db.gradeAssignment(
        row("id", id, "score", score, "feedback", feedback, "grader", Current.actor().id()));
    users.audit(
        Current.actor().id(), "ASSIGNMENT_GRADE", Long.toString(id), score + ":" + feedback);
  }

  @Transactional
  public void feedback(long course, int rating, String comment) {
    long user = Current.actor().id();
    Problem.require(courses.completed(user, course) > 0, 409, "完成课程后可评价");
    db.feedback(user, course, rating, comment);
  }

  public Map<String, Object> coursework(long course) {
    Map<String, Object> c = access.readable(course);
    Actor a = Current.actor();
    boolean manager = a.admin() || id(c, "owner_id") == a.id();
    if (!manager) access.enrolled(a.id(), course);
    return row(
        "assignments",
        db.assignments(course),
        "submissions",
        db.submissions(course, manager, a.id()));
  }
}
