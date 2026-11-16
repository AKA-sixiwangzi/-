package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.Problem;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningRequestService {
  private final LearningRequestMapper db;
  private final CourseMapper courses;
  private final IdentityMapper users;

  public LearningRequestService(
      LearningRequestMapper db, CourseMapper courses, IdentityMapper users) {
    this.db = db;
    this.courses = courses;
    this.users = users;
  }

  private Actor requireRequester() {
    Actor actor = Current.actor();
    Problem.require(actor.admin() || actor.roles().contains("MANAGER"), 403, "需要部门负责人或组织发展部权限");
    return actor;
  }

  public List<Map<String, Object>> list() {
    Actor actor = requireRequester();
    return db.list(actor.admin(), actor.id()).stream().map(this::details).toList();
  }

  private Map<String, Object> details(Map<String, Object> request) {
    Map<String, Object> result = new HashMap<>(request);
    result.put("courses", db.requestCourses(id(request, "id")));
    result.put("users", db.requestUsers(id(request, "id")));
    return result;
  }

  public Map<String, Object> options() {
    Actor actor = requireRequester();
    List<Map<String, Object>> paths = db.eligiblePaths(actor.admin(), actor.id()).stream()
        .map(path -> {
          Map<String, Object> result = new HashMap<>(path);
          result.put("user_ids", db.pathUsers(id(path, "id"), actor.admin(), actor.id()));
          return result;
        }).toList();
    return row("users", db.eligibleUsers(actor.admin(), actor.id()),
        "courses", db.publishedCourses(), "paths", paths);
  }

  private SortedSet<Long> ids(List<Long> values, int maximum) {
    Problem.require(values != null && !values.isEmpty() && values.size() <= maximum,
        400, "请选择有效的课程和员工，且不要超过批量上限");
    Problem.require(values.stream().allMatch(value -> value != null && value > 0),
        400, "课程或员工编号不合法");
    return new TreeSet<>(values);
  }

  private String text(String value) {
    Problem.require(value != null && !value.isBlank() && value.length() <= 1000,
        400, "请填写不超过1000字的原因或处理说明");
    return value.trim();
  }

  private void validateCourses(Set<Long> courseIds) {
    for (long course : courseIds) {
      Map<String, Object> value = found(courses.lockCourse(course));
      Problem.require("PUBLISHED".equals(value.get("status")), 409, "所选课程尚未发布或已下架");
    }
  }

  private Map<Long, Map<String, Object>> lockPeople(Set<Long> userIds) {
    Map<Long, Map<String, Object>> result = new HashMap<>();
    for (long user : new TreeSet<>(userIds)) result.put(user, found(users.lockUser(user)));
    return result;
  }

  private void validatePeople(Set<Long> userIds, long requester, boolean admin, Long path) {
    validatePeople(userIds, requester, admin, path, lockPeople(userIds));
  }

  private void validatePeople(Set<Long> userIds, long requester, boolean admin, Long path,
      Map<Long, Map<String, Object>> lockedPeople) {
    Set<Long> departments = admin ? Set.of() : new HashSet<>(db.managedDepartments(requester));
    if (path != null) {
      Problem.require(path > 0, 400, "成长路线编号不合法");
      Problem.require(Boolean.TRUE.equals(found(db.path(path)).get("published")),
          409, "关联成长路线尚未发布");
    }
    for (long user : userIds) {
      Map<String, Object> employee = lockedPeople.get(user);
      Problem.require(Boolean.TRUE.equals(employee.get("active")), 409, "所选员工已停用");
      Object department = employee.get("department_id");
      Problem.require(admin || department instanceof Number number
          && departments.contains(number.longValue()), 403, "只能为授权部门内的员工申请课程");
      if (path != null) Problem.require(db.assigned(path, user) > 0,
          409, "所选员工未分配该成长路线，请选择路线内的员工或使用独立加课申请");
    }
  }

  @Transactional
  public Map<String, Object> create(Long path, List<Long> courseIds, List<Long> userIds, String reason) {
    Actor actor = requireRequester();
    String why = text(reason);
    SortedSet<Long> selectedCourses = ids(courseIds, 100), selectedUsers = ids(userIds, 1000);
    validateCourses(selectedCourses);
    validatePeople(selectedUsers, actor.id(), actor.admin(), path);
    Map<String, Object> request = row("requester_id", actor.id(), "path_id", path,
        "reason", why, "status", "PENDING");
    db.create(request);
    long requestId = id(request, "id");
    for (long course : selectedCourses) db.course(requestId, course);
    for (long user : selectedUsers) db.user(requestId, user);
    users.audit(actor.id(), "LEARNING_REQUEST_CREATE", Long.toString(requestId), why);
    for (long admin : users.admins())
      users.notifyUser(admin, "待处理加课需求 #" + requestId, why);
    return details(request);
  }

  private void grant(Set<Long> courseIds, Set<Long> userIds, String reason, boolean individualAudit) {
    for (long user : userIds) {
      for (long course : courseIds) {
        courses.enroll(user, course);
        if (individualAudit) users.audit(Current.actor().id(), "COURSE_GRANT",
            "user=" + user + ";course=" + course, reason);
      }
      users.notifyUser(user, "已为你开通新的学习课程", reason);
    }
  }

  @Transactional
  public void review(long requestId, boolean approve, String note) {
    Actor actor = Current.actor();
    actor.requireAdmin();
    String explanation = text(note);
    Map<String, Object> request = found(db.lockRequest(requestId));
    Problem.require("PENDING".equals(request.get("status")), 409, "该需求已处理，请勿重复审批");
    long requester = id(request, "requester_id");
    if (approve) {
      SortedSet<Long> selectedCourses = ids(db.requestCourses(requestId).stream()
          .map(course -> id(course, "id")).toList(), 100);
      SortedSet<Long> selectedUsers = ids(db.requestUsers(requestId).stream()
          .map(user -> id(user, "id")).toList(), 1000);
      validateCourses(selectedCourses);
      SortedSet<Long> peopleToLock = new TreeSet<>(selectedUsers);
      peopleToLock.add(requester);
      Map<Long, Map<String, Object>> lockedPeople = lockPeople(peopleToLock);
      Map<String, Object> owner = lockedPeople.get(requester);
      Problem.require(Boolean.TRUE.equals(owner.get("active")), 409, "申请人账号已停用，请驳回或重新提交");
      Set<String> roles = db.requesterRoles(requester);
      Problem.require(roles.contains("ADMIN") || roles.contains("MANAGER"),
          409, "申请人已无加课申请权限，请驳回或重新提交");
      Long path = request.get("path_id") instanceof Number number ? number.longValue() : null;
      validatePeople(selectedUsers, requester, roles.contains("ADMIN"), path, lockedPeople);
      grant(selectedCourses, selectedUsers, "加课需求 #" + requestId + " 已通过：" + explanation, false);
    }
    Problem.require(db.review(requestId, actor.id(), approve ? "APPROVED" : "REJECTED", explanation) == 1,
        409, "该需求已处理，请刷新");
    users.audit(actor.id(), approve ? "LEARNING_REQUEST_APPROVE" : "LEARNING_REQUEST_REJECT",
        Long.toString(requestId), explanation);
    users.notifyUser(requester, approve ? "加课需求已通过 #" + requestId : "加课需求已驳回 #" + requestId,
        explanation);
  }

  @Transactional
  public Map<String, Object> directGrant(List<Long> courseIds, List<Long> userIds, String reason) {
    Actor actor = Current.actor();
    actor.requireAdmin();
    String why = text(reason);
    SortedSet<Long> selectedCourses = ids(courseIds, 100), selectedUsers = ids(userIds, 1000);
    validateCourses(selectedCourses);
    validatePeople(selectedUsers, actor.id(), true, null);
    grant(selectedCourses, selectedUsers, why, true);
    return row("user_count", selectedUsers.size(), "course_count", selectedCourses.size());
  }
}
