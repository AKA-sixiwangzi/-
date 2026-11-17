package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.Problem;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanOperationsService {
  private final PlanOperationsMapper db;
  private final IdentityMapper users;
  private final Clock clock;

  public PlanOperationsService(PlanOperationsMapper db, IdentityMapper users, Clock clock) {
    this.db = db;
    this.users = users;
    this.clock = clock;
  }

  private long responsible(Map<String, Object> plan) {
    return id(plan, plan.get("responsible_user_id") == null ? "owner_id" : "responsible_user_id");
  }

  private boolean full(Map<String, Object> plan, Actor actor) {
    return actor.admin() || responsible(plan) == actor.id();
  }

  private int integer(Map<String, Object> plan, String key, int fallback) {
    return plan.get(key) instanceof Number value ? value.intValue() : fallback;
  }

  private boolean bool(Object value, boolean fallback) {
    if (value == null) return fallback;
    return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
  }

  @Transactional
  public void rules(long planId, Long responsibleUserId, int before, int every, boolean pushResults) {
    Actor actor = Current.actor();
    Map<String, Object> plan = found(db.lockPlan(planId));
    Problem.require(full(plan, actor), 403, "只有组织发展部或计划负责人可以调整提醒");
    Problem.require(Set.of("DRAFT", "PUBLISHED").contains(str(plan, "status")), 409, "已关闭计划不能调整提醒");
    long assignee = responsibleUserId == null ? responsible(plan) : responsibleUserId;
    Problem.require(actor.admin() || assignee == responsible(plan), 403, "请由组织发展部调整计划负责人");
    Problem.require(before >= 0 && before <= 8760 && every >= 1 && every <= 720,
        400, "提醒提前时间为0至8760小时，重复间隔为1至720小时");
    Problem.require(Boolean.TRUE.equals(found(users.profile(assignee)).get("active")), 400, "负责人必须是启用员工");
    db.rules(row("id", planId, "responsible_user_id", assignee,
        "remind_before_hours", before, "remind_every_hours", every, "push_results", pushResults));
    users.audit(actor.id(), "PLAN_REMINDER_RULES", Long.toString(planId),
        "responsible=" + assignee + "; before=" + before + "; every=" + every + "; results=" + pushResults);
    if (assignee != responsible(plan))
      users.notifyUser(assignee, "已指定为培训计划负责人", str(plan, "title"));
  }

  public List<Map<String, Object>> teachingPlans() {
    Actor actor = Current.actor();
    return db.accessiblePlans(actor.admin(), actor.roles().contains("INSTRUCTOR"), actor.id()).stream()
        .map(plan -> {
          Map<String, Object> detail = teachingPlan(id(plan, "id"));
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> courseRows = (List<Map<String, Object>>) detail.get("courses");
          Map<String, Object> result = new HashMap<>(plan);
          int tasks = 0, completed = 0;
          Set<String> pending = new HashSet<>();
          for (Map<String, Object> course : courseRows) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> learners = (List<Map<String, Object>>) course.get("learners");
            tasks += learners.size();
            for (Map<String, Object> learner : learners) {
              if (bool(learner.get("completed"), false)) completed++;
              @SuppressWarnings("unchecked")
              List<Map<String, Object>> examRows = (List<Map<String, Object>>) learner.get("exam_results");
              @SuppressWarnings("unchecked")
              List<Map<String, Object>> assignmentRows = (List<Map<String, Object>>) learner.get("assignment_results");
              for (Map<String, Object> exam : examRows)
                if ("PENDING_GRADE".equals(exam.get("status")))
                  pending.add("exam:" + learner.get("id") + ":" + exam.get("exam_id"));
              for (Map<String, Object> assignment : assignmentRows)
                if ("SUBMITTED".equals(assignment.get("status")))
                  pending.add("assignment:" + learner.get("id") + ":" + assignment.get("assignment_id"));
            }
          }
          @SuppressWarnings("unchecked")
          List<Map<String, Object>> planExams = (List<Map<String, Object>>) detail.get("plan_exams");
          for (Map<String, Object> exam : planExams) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) exam.get("results");
            for (Map<String, Object> examResult : results)
              if ("PENDING_GRADE".equals(examResult.get("status")))
                pending.add("exam:" + examResult.get("user_id") + ":" + examResult.get("exam_id"));
          }
          result.put("course_count", courseRows.size());
          result.put("member_count", ((List<?>) detail.get("members")).size());
          result.put("assigned_tasks", tasks);
          result.put("completed_tasks", completed);
          result.put("pending_grade", pending.size());
          result.put("can_manage_reminders", detail.get("can_manage_reminders"));
          return result;
        }).toList();
  }

  public Map<String, Object> teachingPlan(long planId) {
    Actor actor = Current.actor();
    Map<String, Object> plan = found(db.plan(planId));
    boolean full = full(plan, actor);
    boolean teacher = actor.roles().contains("INSTRUCTOR");
    List<Map<String, Object>> visible = full || teacher
        ? db.visibleCourses(planId, full, actor.id()) : List.of();
    Problem.require(full || teacher && "PUBLISHED".equals(plan.get("status")) && !visible.isEmpty(),
        403, "只能查看本人授课或负责的培训计划");
    List<Map<String, Object>> courseRows = new ArrayList<>();
    for (Map<String, Object> course : visible) {
      long courseId = id(course, "id");
      List<Map<String, Object>> learners = db.learners(planId, courseId).stream().map(learner -> {
        Map<String, Object> result = new HashMap<>(learner);
        result.put("exam_results", db.learnerExams(planId, courseId, id(learner, "id")));
        result.put("assignment_results", db.learnerAssignments(courseId, id(learner, "id")));
        return result;
      }).toList();
      courseRows.add(row("course", course, "materials", db.materials(courseId),
          "exams", db.courseExams(planId, courseId), "assignments", db.assignments(courseId), "learners", learners));
    }
    List<Map<String, Object>> examStates = db.examStates(planId);
    List<Map<String, Object>> planExams = db.visiblePlanExams(planId, full, actor.id()).stream().map(exam -> {
      Map<String, Object> result = new HashMap<>(exam);
      result.put("results", examStates.stream()
          .filter(state -> id(state, "exam_id") == id(exam, "id")).toList());
      return result;
    }).toList();
    return row("plan", plan, "members", db.members(planId), "courses", courseRows, "plan_exams", planExams,
        "offline_sessions", db.offlineSessions(planId, full, actor.id()), "can_manage_reminders", full);
  }

  public List<Long> publishedPlans() {
    return db.publishedPlans();
  }

  @Transactional
  public Map<String, Object> remind(long planId) {
    Map<String, Object> plan = found(db.lockPlan(planId));
    Problem.require(full(plan, Current.actor()), 403, "只有组织发展部或计划负责人可以发送提醒");
    Problem.require("PUBLISHED".equals(plan.get("status")), 409, "仅已发布计划可以发送提醒");
    Map<String, Object> result = dispatch(planId, plan);
    users.audit(Current.actor().id(), "PLAN_REMIND", Long.toString(planId), "sent=" + result.get("sent"));
    return result;
  }

  @Transactional
  public Map<String, Object> scheduledDispatch(long planId) {
    Map<String, Object> plan = found(db.lockPlan(planId));
    if (!"PUBLISHED".equals(plan.get("status"))) return row("sent", 0);
    return dispatch(planId, plan);
  }

  private int pendingGrades(List<Map<String, Object>> exams, List<Map<String, Object>> assignments) {
    return (int) (exams.stream().filter(exam -> "PENDING_GRADE".equals(exam.get("status"))).count()
        + assignments.stream().filter(assignment -> "SUBMITTED".equals(assignment.get("status"))).count());
  }

  private String digest(List<Map<String, Object>> courseRows, List<Map<String, Object>> exams,
      List<Map<String, Object>> assignments) {
    try {
      MessageDigest hash = MessageDigest.getInstance("SHA-256");
      for (List<Map<String, Object>> rows : List.of(courseRows, exams, assignments)) {
        hash.update((byte) '[');
        for (Map<String, Object> row : rows) {
          for (Map.Entry<String, Object> entry : new TreeMap<>(row).entrySet()) {
            // Length prefixes distinguish text containing delimiters from separate fields.
            String key = entry.getKey(), value = Objects.toString(entry.getValue(), "<null>");
            hash.update((key.length() + ":" + key + value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
          }
          hash.update((byte) ';');
        }
        hash.update((byte) ']');
      }
      return HexFormat.of().formatHex(hash.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private int notifyOnce(long plan, long recipient, String kind, String state, String title, String body) {
    if (db.claimNotice(plan, recipient, kind, state) != 1) return 0;
    users.notifyUser(recipient, title, body);
    return 1;
  }

  private String examSummary(List<Map<String, Object>> exams) {
    StringBuilder result = new StringBuilder();
    int count = 0;
    for (Map<String, Object> exam : exams) {
      if ("NOT_STARTED".equals(exam.get("status"))) continue;
      if (count++ >= 3) break;
      result.append(" 员工#").append(exam.get("user_id")).append(" 考试#").append(exam.get("exam_id")).append("：");
      if ("PENDING_GRADE".equals(exam.get("status"))) result.append("待评阅，尚未最终出分");
      else if ("GRADED".equals(exam.get("status")))
        result.append("最终成绩 ").append(exam.get("score")).append("，")
            .append(bool(exam.get("passed"), false) ? "已通过" : "未通过");
      else result.append("考试进行中");
      result.append("；");
    }
    return result.toString();
  }

  private Map<String, Object> dispatch(long planId, Map<String, Object> plan) {
    int sent = 0;
    long responsible = responsible(plan);
    Map<String, Object> person = users.profile(responsible);
    boolean activeResponsible = person != null && Boolean.TRUE.equals(person.get("active"));
    List<Map<String, Object>> courseRows = db.courseStates(planId), examRows = db.examStates(planId),
        assignmentRows = db.assignmentStates(planId), pending = db.pendingLearners(planId);
    int grading = pendingGrades(examRows, assignmentRows);
    if (activeResponsible && bool(plan.get("push_results"), true)) {
      long completedCourses = courseRows.stream().filter(course -> bool(course.get("completed"), false)).count();
      long gradedExams = examRows.stream().filter(exam -> "GRADED".equals(exam.get("status"))).count();
      long passedExams = examRows.stream().filter(exam -> bool(exam.get("passed"), false)).count();
      long gradedAssignments = assignmentRows.stream().filter(a -> "GRADED".equals(a.get("status"))).count();
      sent += notifyOnce(planId, responsible, "RESULT_DIGEST", digest(courseRows, examRows, assignmentRows),
          "培训学习与考核结果更新 #" + planId,
          str(plan, "title") + "：课程完成 " + completedCourses + "/" + courseRows.size()
              + "；考试已出分 " + gradedExams + "/" + examRows.size() + "，通过 " + passedExams
              + "；作业已批阅 " + gradedAssignments + "/" + assignmentRows.size() + "；待评阅 " + grading
              + "。" + examSummary(examRows) + " 请在授课计划看板查看全部学员明细。");
    }
    long now = clock.millis(), due = ExamService.millis(plan.get("due_at")), start = ExamService.millis(plan.get("start_at"));
    int every = Math.max(1, integer(plan, "remind_every_hours", 24));
    int before = Math.max(0, integer(plan, "remind_before_hours", 72));
    if (now >= start && now >= due - before * 3600000L) {
      boolean overdue = now >= due;
      String period = Long.toString(Math.floorDiv(now, every * 3600000L));
      String kind = overdue ? "OVERDUE" : "DUE";
      String title = (overdue ? "培训已逾期提醒 #" : "培训到期提醒 #") + planId;
      for (Map<String, Object> learner : pending)
        sent += notifyOnce(planId, id(learner, "id"), "LEARNER_" + kind, period, title,
            str(plan, "title") + "：请完成尚未完成的课程、考试或作业。已提交待评阅的任务请等待评阅结果。");
      if (activeResponsible && (!pending.isEmpty() || grading > 0))
        sent += notifyOnce(planId, responsible, "RESPONSIBLE_" + kind, period, title,
            str(plan, "title") + "：尚有 " + pending.size() + " 名学员未完成培训，另有 " + grading + " 项考核待评阅，请及时跟进。");
    }
    return row("sent", sent, "pending_learners", pending.size(), "pending_grade", grading);
  }
}
