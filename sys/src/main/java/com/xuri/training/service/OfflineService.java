package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.Problem;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfflineService {
  private final OfflineMapper db;
  private final IdentityMapper users;
  private final CourseMapper courses;
  private final Clock clock;

  public OfflineService(OfflineMapper db, IdentityMapper users, CourseMapper courses, Clock clock) {
    this.db = db;
    this.users = users;
    this.courses = courses;
    this.clock = clock;
  }

  public record Attendance(long userId, String status, Integer score, String note) {}

  private boolean instructor(Actor actor) {
    return actor.roles().contains("INSTRUCTOR");
  }

  private boolean manage(Actor actor, Map<String, Object> session) {
    return actor.admin() || instructor(actor) && id(session, "instructor_id") == actor.id();
  }

  private Actor requireInstructor() {
    Actor actor = Current.actor();
    Problem.require(actor.admin() || instructor(actor), 403, "需要内训师或组织发展部权限");
    return actor;
  }

  private Instant instant(Object value) {
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof LocalDateTime time) return time.toInstant(ZoneOffset.UTC);
    if (value instanceof OffsetDateTime time) return time.toInstant();
    if (value instanceof Instant time) return time;
    throw new Problem(500, "线下培训时间数据不正确");
  }

  private Map<String, Object> credited(Map<String, Object> record, Map<String, Object> session) {
    Map<String, Object> result = new HashMap<>(record);
    Object status = record.containsKey("attendance_status")
        ? record.get("attendance_status") : record.get("status");
    if (status != null) result.put("status", status);
    Instant start = instant(session.get("start_at")), end = instant(session.get("end_at"));
    long seconds = "PRESENT".equals(status) && !clock.instant().isBefore(end)
        ? Math.max(0, Duration.between(start, end).getSeconds()) : 0;
    result.put("credited_hours", seconds / 3600.0);
    result.put("credited_seconds", seconds);
    return result;
  }

  public List<Map<String, Object>> list() {
    Actor actor = Current.actor();
    return db.list(actor.admin(), instructor(actor), actor.id()).stream().map(session -> {
      Map<String, Object> result = credited(session, session);
      result.put("can_manage", manage(actor, session));
      return result;
    }).toList();
  }

  public Map<String, Object> detail(long sessionId) {
    Actor actor = Current.actor();
    Map<String, Object> session = found(db.session(sessionId));
    boolean all = manage(actor, session);
    if (!all) Problem.require(db.attendanceRow(sessionId, actor.id()) != null,
        403, "无权查看该场线下培训");
    List<Map<String, Object>> attendance = db.attendance(sessionId, all, actor.id()).stream()
        .map(record -> credited(record, session)).toList();
    return row("session", session, "attendance", attendance, "can_manage", all);
  }

  public Map<String, Object> personal() {
    long user = Current.actor().id();
    List<Map<String, Object>> records = db.list(false, false, user).stream()
        .map(session -> credited(session, session)).toList();
    double hours = records.stream().mapToDouble(record -> num(record, "credited_hours")).sum();
    long attended = records.stream().filter(record -> num(record, "credited_seconds") > 0).count();
    return row("records", records, "credited_hours", hours, "attended_sessions", attended);
  }

  public Map<String, Object> options() {
    Actor actor = requireInstructor();
    Map<Long, List<Long>> enrolled = new HashMap<>();
    for (Map<String, Object> enrollment : db.eligibleEnrollments(actor.admin(), actor.id()))
      enrolled.computeIfAbsent(id(enrollment, "user_id"), key -> new ArrayList<>())
          .add(id(enrollment, "course_id"));
    List<Map<String, Object>> people = db.eligibleUsers(actor.admin(), actor.id()).stream().map(user -> {
      Map<String, Object> result = new HashMap<>(user);
      result.put("course_ids", enrolled.getOrDefault(id(user, "id"), List.of()));
      return result;
    }).toList();
    List<Map<String, Object>> plans = db.eligiblePlans(actor.admin(), actor.id()).stream().map(plan -> {
      Map<String, Object> result = new HashMap<>(plan);
      result.put("user_ids", db.planUsers(id(plan, "id"), actor.admin(), actor.id()));
      result.put("course_ids", db.planCourses(id(plan, "id"), actor.admin(), actor.id()));
      return result;
    }).toList();
    return row("users", people,
        "instructors", db.instructors(actor.admin(), actor.id()),
        "courses", db.eligibleCourses(actor.admin(), actor.id()),
        "plans", plans);
  }

  private String text(String value, int maximum, String label) {
    Problem.require(value != null && !value.isBlank() && value.length() <= maximum,
        400, "请填写有效的" + label);
    return value.trim();
  }

  @Transactional
  public Map<String, Object> create(String title, Long plan, Long course, long teacher,
      OffsetDateTime start, OffsetDateTime end, String location, List<Long> userIds) {
    Actor actor = requireInstructor();
    String name = text(title, 200, "培训名称"), place = text(location, 200, "培训地点");
    Problem.require(start != null && end != null && end.isAfter(start)
        && Duration.between(start.toInstant(), end.toInstant()).compareTo(Duration.ofHours(24)) <= 0,
        400, "培训结束时间应晚于开始时间，且单场不超过24小时");
    Problem.require(teacher > 0 && (actor.admin() || teacher == actor.id()),
        403, "内训师只能安排本人授课的线下培训");
    Problem.require(userIds != null && !userIds.isEmpty() && userIds.size() <= 1000
        && userIds.stream().allMatch(value -> value != null && value > 0),
        400, "请至少选择一名有效员工");
    SortedSet<Long> members = new TreeSet<>(userIds);
    if (course != null) {
      Problem.require(course > 0, 400, "课程编号不合法");
      Map<String, Object> linkedCourse = found(courses.lockCourse(course));
      Problem.require("PUBLISHED".equals(linkedCourse.get("status")), 409, "关联课程尚未发布或已下架");
      Problem.require(actor.admin() || id(linkedCourse, "owner_id") == actor.id(),
          403, "内训师只能关联自己的课程");
    }
    if (plan != null) {
      Problem.require(plan > 0, 400, "计划编号不合法");
      Problem.require("PUBLISHED".equals(found(db.plan(plan)).get("status")),
          409, "关联计划尚未发布");
      if (!actor.admin() && course == null) Problem.require(db.authorizedPlan(plan, actor.id()) > 0,
          403, "只能关联本人负责或包含本人授课课程的培训计划");
      if (course != null) Problem.require(db.planCourse(plan, course) > 0,
          400, "关联课程不属于所选培训计划");
    }
    SortedSet<Long> people = new TreeSet<>(members);
    people.add(teacher);
    Map<Long, Map<String, Object>> locked = new HashMap<>();
    for (long user : people) locked.put(user, found(users.lockUser(user)));
    Problem.require(Boolean.TRUE.equals(locked.get(teacher).get("active"))
        && users.roles(teacher).contains("INSTRUCTOR"), 400, "请选择启用的内训师");
    for (long user : members) {
      Problem.require(Boolean.TRUE.equals(locked.get(user).get("active")), 409, "不能分配停用员工");
      if (!actor.admin()) Problem.require(course != null ? courses.enrolled(user, course) > 0
          : db.ownedLearner(actor.id(), user) > 0, 403, "只能为自己课程的学员安排线下培训");
      if (plan != null) Problem.require(db.planMember(plan, user) > 0,
          400, "所选员工不属于关联培训计划");
    }
    Map<String, Object> session = row("title", name, "plan_id", plan, "course_id", course,
        "instructor_id", teacher, "start_at", start.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
        "end_at", end.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
        "location", place, "created_by", actor.id());
    db.create(session);
    long sessionId = id(session, "id");
    for (long user : members) {
      db.register(sessionId, user);
      users.notifyUser(user, "线下培训安排 #" + sessionId, name + "；地点：" + place
          + "；时间：" + start + " 至 " + end);
    }
    users.audit(actor.id(), "OFFLINE_CREATE", Long.toString(sessionId), name + "；地点：" + place);
    return session;
  }

  @Transactional
  public void record(long sessionId, List<Attendance> records, String reason) {
    Actor actor = Current.actor();
    Map<String, Object> session = found(db.lockSession(sessionId));
    Problem.require(manage(actor, session), 403, "仅授课内训师或组织发展部可登记出勤");
    String why = text(reason, 1000, "登记或更正原因");
    Problem.require(!clock.instant().isBefore(instant(session.get("start_at"))),
        409, "培训尚未开始，不能提前登记出勤");
    Problem.require(records != null && !records.isEmpty() && records.size() <= 1000,
        400, "请提供有效的出勤记录");
    SortedMap<Long, Attendance> selected = new TreeMap<>();
    for (Attendance record : records) {
      Problem.require(record != null && record.userId() > 0
          && Set.of("PRESENT", "ABSENT", "EXCUSED").contains(Objects.toString(record.status(), ""))
          && (record.score() == null || record.score() >= 0 && record.score() <= 100)
          && (record.note() == null || record.note().length() <= 1000), 400, "出勤状态、分数或备注不合法");
      Problem.require(selected.put(record.userId(), record) == null, 400, "同一员工不可重复登记");
    }
    Map<Long, Map<String, Object>> previous = new HashMap<>();
    for (long user : selected.keySet()) {
      Problem.require(Boolean.TRUE.equals(found(users.lockUser(user)).get("active")),
          409, "不能为停用员工登记出勤");
      previous.put(user, found(db.attendanceRow(sessionId, user)));
    }
    for (Attendance record : selected.values()) {
      long user = record.userId();
      Map<String, Object> before = previous.get(user);
      Map<String, Object> change = row("session_id", sessionId, "user_id", user,
          "status", record.status(), "score", record.score(), "note", record.note(), "recorded_by", actor.id());
      Problem.require(db.record(change) == 1, 409, "出勤记录已变更，请刷新后重试");
      String target = "session=" + sessionId + ";user=" + user;
      users.audit(actor.id(), "OFFLINE_ATTENDANCE", target, "状态：" + before.get("status")
          + " -> " + record.status() + "；分数：" + before.get("score") + " -> " + record.score() + "；原因：" + why);
      if (!Objects.equals(before.get("note"), record.note())) {
        users.audit(actor.id(), "OFFLINE_NOTE_BEFORE", target, Objects.toString(before.get("note"), ""));
        users.audit(actor.id(), "OFFLINE_NOTE_AFTER", target, Objects.toString(record.note(), ""));
      }
      users.notifyUser(user, "线下培训出勤记录已更新 #" + sessionId,
          str(session, "title") + "；状态：" + record.status() + "；成绩："
              + Objects.toString(record.score(), "未评分") + "；备注：" + Objects.toString(record.note(), ""));
    }
  }
}
