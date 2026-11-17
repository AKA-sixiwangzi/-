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
    return path(title, position, grade, null, null);
  }

  @Transactional
  public Map<String, Object> path(
      String title, Long position, Long grade, Boolean allDepartments, List<Long> departmentIds) {
    Current.actor().requireAdmin();
    Problem.require(
        position == null || users.positions().stream()
            .anyMatch(p -> IdentityMapper.fixedPosition(p) && id(p, "id") == position),
        400,
        "岗位仅支持专业岗、管理岗、综合岗，请刷新岗位选项");
    List<Long> requested = departmentIds == null ? List.of() : departmentIds;
    Problem.require(
        requested.size() <= 1000
            && requested.stream().allMatch(id -> id != null && id > 0),
        400,
        "部门编号不合法");
    Set<Long> departments = new TreeSet<>(requested);
    boolean all = allDepartments == null ? departments.isEmpty() : allDepartments;
    Problem.require(!all || departments.isEmpty(), 400, "选择所有部门时不能同时指定部门");
    Problem.require(all || !departments.isEmpty(), 400, "请至少选择一个适用部门");
    List<Map<String, Object>> applicable = new ArrayList<>();
    if (!all) {
      Map<Long, Map<String, Object>> existing = new HashMap<>();
      for (Map<String, Object> department : users.departments()) {
        existing.put(id(department, "id"), department);
      }
      for (long department : departments) {
        Problem.require(existing.containsKey(department), 400, "所选部门不存在，请刷新部门列表");
        Map<String, Object> found = existing.get(department);
        applicable.add(row("id", department, "name", found.get("name")));
      }
    }
    Map<String, Object> r =
        row(
            "title", title,
            "position_id", position,
            "grade_id", grade,
            "all_departments", all);
    db.createPath(r);
    for (long department : departments) db.pathDepartment(id(r, "id"), department);
    r.put("department_ids", new ArrayList<>(departments));
    r.put("departments", applicable);
    return r;
  }

  public List<Map<String, Object>> paths() {
    return db.paths(Current.actor().admin(), Current.actor().id()).stream()
        .map(this::withDepartmentScope)
        .toList();
  }

  private boolean allDepartments(Map<String, Object> path) {
    Object value = path.get("all_departments");
    return value == null || Boolean.TRUE.equals(value)
        || value instanceof Number number && number.intValue() != 0;
  }

  private Map<String, Object> withDepartmentScope(Map<String, Object> path) {
    Map<String, Object> result = new HashMap<>(path);
    boolean all = allDepartments(path);
    List<Map<String, Object>> departments = all ? List.of() : db.pathDepartments(id(path, "id"));
    result.put("all_departments", all);
    result.put("departments", departments);
    result.put("department_ids", departments.stream().map(d -> id(d, "id")).toList());
    return result;
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
    assignMembers(path, ids, null, null);
  }

  @Transactional
  public void assign(long path, List<Long> ids, Long departmentId, Long positionId) {
    Current.actor().requireAdmin();
    Problem.require(departmentId != null && positionId != null, 400, "请先选择部门和岗位");
    Problem.require(
        users.departments().stream().anyMatch(d -> id(d, "id") == departmentId),
        400,
        "所选部门不存在，请刷新后重试");
    Problem.require(
        users.positions().stream()
            .anyMatch(p -> IdentityMapper.fixedPosition(p) && id(p, "id") == positionId),
        400,
        "岗位仅支持专业岗、管理岗、综合岗，请刷新岗位选项");
    assignMembers(path, ids, departmentId, positionId);
  }

  private void assignMembers(long path, List<Long> ids, Long departmentId, Long positionId) {
    Current.actor().requireAdmin();
    Problem.require(
        ids != null && !ids.isEmpty() && ids.size() <= 1000
            && ids.stream().allMatch(user -> user != null && user > 0),
        400,
        "请至少选择一名有效员工");
    Map<String, Object> p = found(db.lockPath(path));
    Problem.require(Boolean.TRUE.equals(p.get("published")), 409, "路线未发布");
    Object routePosition = p.get("position_id");
    Problem.require(
        positionId == null || routePosition == null
            || routePosition instanceof Number number && number.longValue() == positionId,
        400,
        "所选岗位不符合路线的适用岗位");
    Set<Long> members = new TreeSet<>(ids);
    boolean all = allDepartments(p);
    Set<Long> departments = new HashSet<>();
    if (!all) {
      for (Map<String, Object> department : db.pathDepartments(path)) {
        departments.add(id(department, "id"));
      }
      Problem.require(!departments.isEmpty(), 409, "路线尚未配置适用部门");
    }
    // Lock and validate the whole batch before writing enrollments or sending notifications.
    for (long user : members) {
      Map<String, Object> employee = found(users.lockUser(user));
      Problem.require(Boolean.TRUE.equals(employee.get("active")), 400, "不能分配已停用的员工");
      Object department = employee.get("department_id");
      Object employeePosition = employee.get("position_id");
      Problem.require(
          all || department instanceof Number number && departments.contains(number.longValue()),
          400,
          "所选员工不在该路线的适用部门范围内");
      Problem.require(
          departmentId == null
              || department instanceof Number number && number.longValue() == departmentId,
          400,
          "所选员工不属于选择的部门");
      Problem.require(
          positionId == null
              || employeePosition instanceof Number number && number.longValue() == positionId,
          400,
          "所选员工不属于选择的岗位");
      Problem.require(
          routePosition == null || routePosition instanceof Number expected
              && employeePosition instanceof Number actual
              && actual.longValue() == expected.longValue(),
          400,
          "所选员工不符合路线的适用岗位");
    }
    for (long user : members) {
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
    return row("path", withDepartmentScope(p), "stages", stages);
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
    return plan(title, start, due, courseIds, memberIds, examIds, null, 72, 24, true);
  }

  @Transactional
  public Map<String, Object> plan(
      String title, LocalDateTime start, LocalDateTime due, List<Long> courseIds,
      List<Long> memberIds, List<Long> examIds, Long responsibleUserId,
      int remindBeforeHours, int remindEveryHours, boolean pushResults) {
    Current.actor().requireAdmin();
    Problem.require(
        title != null && !title.isBlank() && title.length() <= 200 && start != null && due != null
            && due.isAfter(start) && validPlanIds(courseIds, 100, false)
            && validPlanIds(memberIds, 1000, false) && validPlanIds(examIds, 100, true),
        400, "计划时间、课程或人员不合法");
    Problem.require(remindBeforeHours >= 0 && remindBeforeHours <= 8760
        && remindEveryHours >= 1 && remindEveryHours <= 720, 400, "提醒规则不合法");
    long responsible = responsibleUserId == null ? Current.actor().id() : responsibleUserId;
    Problem.require(Boolean.TRUE.equals(found(users.profile(responsible)).get("active")),
        400, "负责人必须是启用员工");
    for (long course : new TreeSet<>(courseIds)) publishedCourse(course);
    for (long exam : new TreeSet<>(examIds)) {
      found(exams.examById(exam));
      Problem.require(
          exams.prerequisites(exam, Current.actor().id()).stream()
              .allMatch(c -> courseIds.contains(id(c, "id"))),
          400,
          "计划课程必须覆盖关联考试的前置课程");
    }
    for (long user : new TreeSet<>(memberIds))
      Problem.require(Boolean.TRUE.equals(found(users.profile(user)).get("active")), 400, "计划不能包含停用员工");
    Map<String, Object> p = row("title", title, "start_at", start, "due_at", due,
        "owner_id", Current.actor().id(), "responsible_user_id", responsible,
        "remind_before_hours", remindBeforeHours, "remind_every_hours", remindEveryHours,
        "push_results", pushResults, "status", "DRAFT");
    db.plan(p);
    long plan = id(p, "id");
    for (long course : new TreeSet<>(courseIds)) db.planCourse(plan, course);
    for (long exam : new TreeSet<>(examIds)) db.planExam(plan, exam);
    for (long user : new TreeSet<>(memberIds)) db.planMember(plan, user);
    users.audit(Current.actor().id(), "PLAN_CREATE", Long.toString(plan), "responsible=" + responsible + "; " + title);
    return p;
  }

  private boolean validPlanIds(List<Long> values, int maximum, boolean emptyAllowed) {
    return values != null && (emptyAllowed || !values.isEmpty()) && values.size() <= maximum
        && values.stream().allMatch(value -> value != null && value > 0);
  }

  @Transactional
  public void publishPlan(long plan) {
    Current.actor().requireAdmin();
    Map<String, Object> p = found(db.lockPlan(plan));
    Problem.require("DRAFT".equals(p.get("status")), 409, "计划已发布");
    Set<Long> members = new TreeSet<>(db.planMembers(plan));
    Problem.require(!members.isEmpty() && !db.planCourses(plan).isEmpty(), 409, "请先配置计划课程和员工");
    long responsible = id(p, p.get("responsible_user_id") == null ? "owner_id" : "responsible_user_id");
    Problem.require(Boolean.TRUE.equals(found(users.profile(responsible)).get("active")), 409, "计划负责人已停用，请先调整负责人");
    for (long course : new TreeSet<>(db.planCourses(plan))) publishedCourse(course);
    for (long user : members)
      Problem.require(Boolean.TRUE.equals(found(users.lockUser(user)).get("active")), 409, "计划中存在停用员工");
    for (long user : members) {
      for (long course : db.planCourses(plan)) courses.enroll(user, course);
      for (long exam : db.planExams(plan)) exams.member(exam, user);
      messages.notify(user, "培训任务已发布", str(p, "title"));
    }
    db.publishPlan(plan);
    messages.notify(responsible, "请跟进培训计划学习与考核结果", str(p, "title"));
    users.audit(Current.actor().id(), "PLAN_PUBLISH", Long.toString(plan), str(p, "title"));
  }

  public Map<String, Object> planDetail(long plan) {
    Map<String, Object> p = found(db.lockPlan(plan));
    Actor a = Current.actor();
    long responsible = id(p, p.get("responsible_user_id") == null ? "owner_id" : "responsible_user_id");
    boolean coordinate = a.admin() || responsible == a.id();
    Problem.require(coordinate || db.planMembers(plan).contains(a.id()), 403, "无权查看该计划");
    return row(
        "plan",
        p,
        "progress",
        db.planProgress(plan, a.id()),
        "exams",
        db.planExamProgress(plan, a.id()),
        "can_manage_reminders", coordinate);
  }
}
