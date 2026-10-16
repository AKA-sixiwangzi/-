package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProgramService {
  private final ProgramMapper db;
  private final CourseMapper courses;
  private final IdentityMapper users;
  private final WarningMapper messages;
  private final ExamMapper exams;

  public ProgramService(
      ProgramMapper db,
      CourseMapper courses,
      IdentityMapper users,
      WarningMapper messages,
      ExamMapper exams) {
    this.db = db;
    this.courses = courses;
    this.users = users;
    this.messages = messages;
    this.exams = exams;
  }

  private void publishedCourse(long id) {
    Problem.require("PUBLISHED".equals(found(courses.course(id)).get("status")), 409, "只能分配已发布课程");
  }

  @Transactional
  public Map<String, Object> path(String title, Long position, Long grade) {
    Current.actor().requireAdmin();
    Map<String, Object> r = row("title", title, "position_id", position, "grade_id", grade);
    db.createPath(r);
    return r;
  }

  @Transactional
  public Map<String, Object> stage(long path, String title, int order, List<Long> courseIds) {
    Current.actor().requireAdmin();
    Problem.require(
        !Boolean.TRUE.equals(found(db.lockPath(path)).get("published")), 409, "已发布路线不能修改，请新建版本");
    Problem.require(!courseIds.isEmpty(), 400, "阶段至少有一门课程");
    Map<String, Object> r = row("path_id", path, "title", title, "sort_order", order);
    db.stage(r);
    for (long course : new HashSet<>(courseIds)) {
      publishedCourse(course);
      db.stageCourse(id(r, "id"), course);
    }
    return r;
  }

  @Transactional
  public void publish(long path) {
    Current.actor().requireAdmin();
    found(db.lockPath(path));
    Problem.require(!db.stages(path).isEmpty(), 400, "请先配置阶段");
    db.publishPath(path);
  }

  @Transactional
  public void assign(long path, List<Long> ids) {
    Current.actor().requireAdmin();
    Map<String, Object> p = found(db.lockPath(path));
    Problem.require(Boolean.TRUE.equals(p.get("published")), 409, "路线未发布");
    for (long user : new TreeSet<>(ids)) {
      found(users.lockUser(user));
      db.assign(path, user);
      for (Map<String, Object> s : db.stages(path))
        for (Map<String, Object> c : db.stageCourses(id(s, "id"), user))
          courses.enroll(user, id(c, "id"));
      messages.notify(user, "新的岗位成长路线", str(p, "title"));
    }
  }

  public boolean stagePassed(long stage, long user) {
    List<Map<String, Object>> cs = db.stageCourses(stage, user);
    return !cs.isEmpty()
        && cs.stream().allMatch(c -> num(c, "completed") == 1)
        && db.stageExams(stage, user).stream().allMatch(e -> num(e, "passed") == 1);
  }

  public void requireStageUnlocked(long stage, long user) {
    Map<String, Object> s = found(db.stageById(stage));
    long path = id(s, "path_id");
    Problem.require(db.assigned(path, user) > 0, 403, "未分配该成长路线");
    for (Map<String, Object> previous : db.stages(path)) {
      if (id(previous, "id") == stage) break;
      Problem.require(stagePassed(id(previous, "id"), user), 409, "请先完成成长路线的前序阶段");
    }
  }

  public Map<String, Object> detail(long path, long user) {
    Map<String, Object> p = found(db.path(path));
    Problem.require(
        Current.actor().admin() || user == Current.actor().id() && db.assigned(path, user) > 0,
        403,
        "无权查看该路线");
    List<Map<String, Object>> stages = new ArrayList<>();
    boolean unlocked = true;
    for (Map<String, Object> s : db.stages(path)) {
      boolean passed = stagePassed(id(s, "id"), user);
      stages.add(
          row(
              "stage",
              s,
              "unlocked",
              unlocked,
              "passed",
              passed,
              "courses",
              db.stageCourses(id(s, "id"), user),
              "exams",
              db.stageExams(id(s, "id"), user)));
      unlocked = unlocked && passed;
    }
    return row("path", p, "stages", stages);
  }

  @Transactional
  public void certificate(long path) {
    long user = Current.actor().id();
    users.lockUser(user);
    Problem.require(db.assigned(path, user) > 0, 403, "路线尚未分配");
    List<Map<String, Object>> ss = db.stages(path);
    Problem.require(
        !ss.isEmpty() && ss.stream().allMatch(s -> stagePassed(id(s, "id"), user)),
        409,
        "尚未满足全部通关要求");
    db.certificate(user, path);
  }

  @Transactional
  public Map<String, Object> plan(
      String title,
      LocalDateTime start,
      LocalDateTime due,
      List<Long> courseIds,
      List<Long> memberIds,
      List<Long> examIds) {
    Current.actor().requireAdmin();
    Problem.require(
        due.isAfter(start) && !courseIds.isEmpty() && !memberIds.isEmpty(), 400, "计划时间、课程或人员不合法");
    Map<String, Object> p =
        row("title", title, "start_at", start, "due_at", due, "owner_id", Current.actor().id());
    db.plan(p);
    long plan = id(p, "id");
    for (long course : new TreeSet<>(courseIds)) {
      publishedCourse(course);
      db.planCourse(plan, course);
    }
    for (long exam : new TreeSet<>(examIds)) {
      found(exams.examById(exam));
      Problem.require(
          exams.prerequisites(exam, Current.actor().id()).stream()
              .allMatch(c -> courseIds.contains(id(c, "id"))),
          400,
          "计划课程必须覆盖关联考试的前置课程");
      db.planExam(plan, exam);
    }
    for (long user : new TreeSet<>(memberIds)) {
      found(users.profile(user));
      db.planMember(plan, user);
    }
    return p;
  }

  @Transactional
  public void publishPlan(long plan) {
    Current.actor().requireAdmin();
    Map<String, Object> p = found(db.lockPlan(plan));
    Problem.require("DRAFT".equals(p.get("status")), 409, "计划已发布");
    for (long user : new TreeSet<>(db.planMembers(plan))) {
      users.lockUser(user);
      for (long course : db.planCourses(plan)) courses.enroll(user, course);
      for (long exam : db.planExams(plan)) exams.member(exam, user);
      messages.notify(user, "培训任务已发布", str(p, "title"));
    }
    db.publishPlan(plan);
  }

  public Map<String, Object> planDetail(long plan) {
    Map<String, Object> p = found(db.lockPlan(plan));
    Actor a = Current.actor();
    Problem.require(a.admin() || db.planMembers(plan).contains(a.id()), 403, "无权查看该计划");
    return row(
        "plan",
        p,
        "progress",
        db.planProgress(plan, a.id()),
        "exams",
        db.planExamProgress(plan, a.id()));
  }
}
