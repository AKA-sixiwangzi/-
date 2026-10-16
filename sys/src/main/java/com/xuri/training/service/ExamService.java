package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExamService {
  private final ExamMapper db;
  private final CourseService courses;
  private final IdentityMapper users;
  private final ProgramService paths;
  private final ProgramMapper programs;
  private final WarningMapper messages;
  private final ObjectMapper json;
  private final Clock clock;

  public ExamService(
      ExamMapper db,
      CourseService courses,
      IdentityMapper users,
      ProgramService paths,
      ProgramMapper programs,
      WarningMapper messages,
      ObjectMapper json,
      Clock clock) {
    this.db = db;
    this.courses = courses;
    this.users = users;
    this.paths = paths;
    this.programs = programs;
    this.messages = messages;
    this.json = json;
    this.clock = clock;
  }

  public String encode(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON格式错误", e);
    }
  }

  public Map<String, Object> decode(String value) {
    try {
      return json.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalArgumentException("JSON格式错误", e);
    }
  }

  @Transactional
  public Map<String, Object> question(
      long course,
      String type,
      String stem,
      Map<String, String> options,
      String answer,
      String explanation,
      String source) {
    courses.manage(course);
    QuestionRules.validate(type, options, answer);
    Map<String, Object> q =
        row(
            "course_id",
            course,
            "owner_id",
            Current.actor().id(),
            "type",
            type,
            "stem",
            stem,
            "options_json",
            encode(options),
            "answer",
            answer,
            "explanation",
            explanation,
            "source",
            source,
            "status",
            "AI".equals(source) ? "DRAFT" : "APPROVED");
    db.question(q);
    return q;
  }

  @Transactional
  public void approveQuestion(long id) {
    Map<String, Object> q = found(db.questionById(id));
    courses.manage(id(q, "course_id"));
    db.approveQuestion(id);
    users.audit(Current.actor().id(), "QUESTION_APPROVE", Long.toString(id), "人工审核题目");
  }

  @Transactional
  public Map<String, Object> paper(String title, List<Long> ids, List<Integer> scores) {
    Problem.require(
        !ids.isEmpty()
            && ids.size() == scores.size()
            && ids.size() <= 200
            && new HashSet<>(ids).size() == ids.size(),
        400,
        "试卷题目数量或分值不合法");
    Map<String, Object> p = row("title", title, "owner_id", Current.actor().id());
    db.paper(p);
    for (int i = 0; i < ids.size(); i++) {
      Map<String, Object> q = found(db.questionById(ids.get(i)));
      courses.manage(id(q, "course_id"));
      Problem.require(
          "APPROVED".equals(q.get("status")) && scores.get(i) > 0 && scores.get(i) <= 100,
          400,
          "题目需审核通过且分值应为1至100");
      db.paperQuestion(
          row(
              "paper",
              id(p, "id"),
              "question",
              ids.get(i),
              "order",
              i + 1,
              "score",
              scores.get(i),
              "snapshot",
              encode(q)));
    }
    return p;
  }

  public Map<String, Object> managePaper(long id) {
    Map<String, Object> p = found(db.paperById(id));
    Problem.require(
        Current.actor().admin() || id(p, "owner_id") == Current.actor().id(), 403, "无权管理该试卷");
    return p;
  }

  public Map<String, Object> paperDetail(long id) {
    Map<String, Object> p = managePaper(id);
    return row("paper", p, "questions", db.paperQuestions(id));
  }

  @Transactional
  public void publish(long id) {
    managePaper(id);
    found(db.lockPaper(id));
    Problem.require(!db.paperQuestions(id).isEmpty(), 400, "试卷没有题目");
    db.publish(id);
  }

  @Transactional
  public Map<String, Object> exam(
      String title,
      long paper,
      Long stage,
      LocalDateTime start,
      LocalDateTime end,
      int minutes,
      int pass,
      int attempts,
      List<Long> prerequisites,
      List<Long> members) {
    Map<String, Object> p = managePaper(paper);
    Problem.require(Boolean.TRUE.equals(p.get("published")), 409, "请先发布试卷");
    Set<Long> required = new TreeSet<>(prerequisites);
    Problem.require(
        !required.isEmpty() && required.containsAll(db.paperCourses(paper)),
        400,
        "考试前置课程必须覆盖试卷关联课程");
    Problem.require(end.isAfter(start) && !members.isEmpty(), 400, "时间或参试人员不合法");
    int total =
        db.paperQuestions(paper).stream().mapToInt(q -> ((Number) q.get("score")).intValue()).sum();
    Problem.require(pass <= total, 400, "及格分不能超过试卷总分");
    for (long c : required) courses.manage(c);
    if (stage != null) {
      Current.actor().requireAdmin();
      Map<String, Object> s = found(programs.stageById(stage));
      Problem.require(
          !Boolean.TRUE.equals(found(programs.lockPath(id(s, "path_id"))).get("published")),
          409,
          "已发布路线不能新增阶段考试，请新建路线版本");
    }
    Map<String, Object> r =
        row(
            "title",
            title,
            "paper_id",
            paper,
            "owner_id",
            Current.actor().id(),
            "stage_id",
            stage,
            "start_at",
            start,
            "end_at",
            end,
            "duration_minutes",
            minutes,
            "pass_score",
            pass,
            "max_attempts",
            attempts);
    db.exam(r);
    long exam = id(r, "id");
    for (long c : required) db.prerequisite(exam, c);
    for (long user : new TreeSet<>(members)) {
      found(users.profile(user));
      db.member(exam, user);
      messages.notify(user, "新的关联课程考试", title);
    }
    return r;
  }

  public static long millis(Object date) {
    if (date instanceof java.sql.Timestamp t) return t.toInstant().toEpochMilli();
    if (date instanceof LocalDateTime d) return d.toInstant(ZoneOffset.UTC).toEpochMilli();
    throw new IllegalArgumentException("不支持的时间格式");
  }

  private Map<String, Object> ownAttempt(String id) {
    Map<String, Object> a = found(db.attemptById(id));
    Problem.require(id(a, "user_id") == Current.actor().id(), 403, "无权操作他人的考试");
    return a;
  }

  private void prerequisites(long exam, long user) {
    Problem.require(db.eligible(exam, user) > 0, 403, "不在本次考试名单中");
    List<Map<String, Object>> ps = db.prerequisites(exam, user);
    Problem.require(
        !ps.isEmpty()
            && ps.stream()
                .allMatch(p -> num(p, "completed") == 1 && "PUBLISHED".equals(p.get("status"))),
        409,
        "请先完成前置课程");
    Map<String, Object> e = found(db.examById(exam));
    if (e.get("stage_id") != null) paths.requireStageUnlocked(id(e, "stage_id"), user);
  }

  @Transactional
  public Map<String, Object> start(long exam) {
    long user = Current.actor().id();
    users.lockUser(user);
    Map<String, Object> e = found(db.examById(exam));
    prerequisites(exam, user);
    long now = clock.millis();
    Problem.require(
        now >= millis(e.get("start_at")) && now < millis(e.get("end_at")), 409, "不在考试开放时间内");
    List<Map<String, Object>> as = db.attempts(exam, user);
    for (Map<String, Object> a : as)
      if ("IN_PROGRESS".equals(a.get("status"))) {
        if (now < id(a, "deadline_ms")) return attemptView(str(a, "id"));
        finish(str(a, "id"));
      }
    as = db.attempts(exam, user);
    Problem.require(as.size() < id(e, "max_attempts"), 409, "考试次数已用完");
    Problem.require(
        as.stream().noneMatch(a -> Boolean.TRUE.equals(a.get("passed"))), 409, "考试已经通过");
    Problem.require(
        as.stream().noneMatch(a -> "PENDING_GRADE".equals(a.get("status"))), 409, "请等待阅卷完成");
    String id = UUID.randomUUID().toString();
    db.attempt(
        row(
            "id",
            id,
            "exam_id",
            exam,
            "user_id",
            user,
            "attempt_no",
            as.size() + 1,
            "started_at_ms",
            now,
            "deadline_ms",
            Math.min(now + id(e, "duration_minutes") * 60000, millis(e.get("end_at")))));
    return attemptView(id);
  }

  public Map<String, Object> attemptView(String id) {
    Map<String, Object> a = ownAttempt(id);
    Map<String, Object> e = found(db.examById(id(a, "exam_id")));
    List<Map<String, Object>> safe = new ArrayList<>();
    for (Map<String, Object> p : db.paperQuestions(id(e, "paper_id"))) {
      Map<String, Object> q = decode(str(p, "snapshot_json"));
      safe.add(
          row(
              "id",
              id(p, "id"),
              "type",
              q.get("type"),
              "stem",
              q.get("stem"),
              "options",
              decode(str(q, "options_json")),
              "score",
              p.get("score")));
    }
    return row("attempt", a, "exam", e, "questions", safe, "answers", db.answers(id));
  }

  @Transactional
  public void save(String id, long question, String answer) {
    users.lockUser(Current.actor().id());
    Map<String, Object> a = ownAttempt(id);
    found(db.lockAttempt(id));
    Problem.require(
        "IN_PROGRESS".equals(a.get("status")) && clock.millis() < id(a, "deadline_ms"),
        409,
        "考试已结束");
    Map<String, Object> e = found(db.examById(id(a, "exam_id")));
    Problem.require(
        db.paperQuestions(id(e, "paper_id")).stream().anyMatch(p -> id(p, "id") == question),
        400,
        "题目不属于本场考试");
    db.answer(id, question, answer);
  }

  @Transactional
  public Map<String, Object> submit(String id) {
    users.lockUser(Current.actor().id());
    ownAttempt(id);
    finish(id);
    return attemptView(id);
  }

  @Transactional
  public void finish(String id) {
    Map<String, Object> a = found(db.lockAttempt(id));
    if (!"IN_PROGRESS".equals(a.get("status"))) return;
    Map<String, Object> e = found(db.examById(id(a, "exam_id")));
    Map<Long, String> answers = new HashMap<>();
    for (Map<String, Object> v : db.answers(id))
      answers.put(id(v, "paper_question_id"), str(v, "answer"));
    boolean pending = false;
    int total = 0;
    for (Map<String, Object> p : db.paperQuestions(id(e, "paper_id"))) {
      long qid = id(p, "id");
      String answer = answers.getOrDefault(qid, "");
      db.answer(id, qid, answer);
      Map<String, Object> q = decode(str(p, "snapshot_json"));
      if ("TEXT".equals(q.get("type")) && !answer.isBlank()) {
        pending = true;
        continue;
      }
      int score =
          !"TEXT".equals(q.get("type")) && QuestionRules.correct(str(q, "answer"), answer)
              ? ((Number) p.get("score")).intValue()
              : 0;
      db.grade(id, qid, score, null);
      total += score;
    }
    db.result(
        row(
            "id",
            id,
            "status",
            pending ? "PENDING_GRADE" : "GRADED",
            "score",
            pending ? null : total,
            "passed",
            !pending && total >= num(e, "pass_score"),
            "submitted",
            clock.millis()));
    messages.notify(id(a, "user_id"), pending ? "考试待人工阅卷" : "考试成绩已发布", str(e, "title"));
  }

  @Transactional
  public void grade(String attempt, long question, int score) {
    Map<String, Object> a = found(db.lockAttempt(attempt));
    Map<String, Object> e = manageExam(id(a, "exam_id"));
    Problem.require(id(a, "user_id") != Current.actor().id(), 403, "不能批改自己的考试");
    Problem.require("PENDING_GRADE".equals(a.get("status")), 409, "考试不处于待阅卷状态");
    Map<String, Object> p =
        db.paperQuestions(id(e, "paper_id")).stream()
            .filter(v -> id(v, "id") == question)
            .findFirst()
            .orElseThrow(() -> new Problem(400, "题目不属于试卷"));
    Problem.require(
        "TEXT".equals(decode(str(p, "snapshot_json")).get("type"))
            && score >= 0
            && score <= num(p, "score"),
        400,
        "只能批改主观题，且不得超出分值范围");
    Problem.require(db.grade(attempt, question, score, Current.actor().id()) == 1, 400, "没有该题作答记录");
    List<Map<String, Object>> answers = db.answers(attempt);
    if (answers.stream().allMatch(v -> v.get("score") != null)) {
      int total = answers.stream().mapToInt(v -> ((Number) v.get("score")).intValue()).sum();
      db.result(
          row(
              "id",
              attempt,
              "status",
              "GRADED",
              "score",
              total,
              "passed",
              total >= num(e, "pass_score"),
              "submitted",
              clock.millis()));
      messages.notify(id(a, "user_id"), "考试成绩已发布", str(e, "title"));
    }
    users.audit(Current.actor().id(), "EXAM_GRADE", attempt, question + ":" + score);
  }

  public Map<String, Object> manageExam(long id) {
    Map<String, Object> e = found(db.examById(id));
    Problem.require(
        Current.actor().admin() || id(e, "owner_id") == Current.actor().id(), 403, "只能管理自己安排的考试");
    return e;
  }

  @Transactional
  public void riskEvent(String attempt, String event) {
    long user = Current.actor().id();
    users.lockUser(user);
    Map<String, Object> a = ownAttempt(attempt);
    Problem.require(
        "IN_PROGRESS".equals(a.get("status")) && clock.millis() < id(a, "deadline_ms"),
        409,
        "考试已结束");
    Problem.require(Set.of("BLUR", "FOCUS").contains(event), 400, "考试事件不合法");
    db.examEvent(attempt, event);
    if ("BLUR".equals(event) && db.blurCount(attempt) == 3) {
      for (long admin : users.admins())
        messages.notify(
            admin,
            "考试风险提醒",
            "员工ID：" + user + "，考试ID：" + a.get("exam_id") + "，已发生至少3次切屏，请核实。风险事件已保存。");
    }
  }

  public Map<String, Object> gradingView(String id) {
    Map<String, Object> a = found(db.attemptById(id));
    Map<String, Object> e = manageExam(id(a, "exam_id"));
    Problem.require(id(a, "user_id") != Current.actor().id(), 403, "不能批改自己的考试");
    return row(
        "attempt", a, "questions", db.paperQuestions(id(e, "paper_id")), "answers", db.answers(id));
  }

  public List<Map<String, Object>> center() {
    long user = Current.actor().id();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> e : db.myExams(user)) {
      long exam = id(e, "id");
      List<Map<String, Object>> p = db.prerequisites(exam, user), as = db.attempts(exam, user);
      String zone =
          p.stream().anyMatch(c -> num(c, "completed") != 1) ? "LEARNING_REQUIRED" : "READY";
      if (as.stream().anyMatch(a -> "IN_PROGRESS".equals(a.get("status")))) zone = "IN_PROGRESS";
      else if (as.stream().anyMatch(a -> Boolean.TRUE.equals(a.get("passed")))) zone = "COMPLETED";
      else if (as.stream().anyMatch(a -> "PENDING_GRADE".equals(a.get("status"))))
        zone = "PENDING_GRADE";
      else if (!as.isEmpty() && as.size() < num(e, "max_attempts")) zone = "RETAKE";
      else if (as.size() >= num(e, "max_attempts")) zone = "ATTEMPTS_EXHAUSTED";
      result.add(row("exam", e, "prerequisites", p, "attempts", as, "zone", zone));
    }
    return result;
  }
}
