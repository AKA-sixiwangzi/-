package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningService {
  private final LearningMapper db;
  private final IdentityMapper users;
  private final CourseMapper courses;
  private final CourseService access;
  private final WarningService warnings;
  private final WarningMapper warningDb;
  private final Clock clock;

  public LearningService(
      LearningMapper db,
      IdentityMapper users,
      CourseMapper courses,
      CourseService access,
      WarningService warnings,
      WarningMapper warningDb,
      Clock clock) {
    this.db = db;
    this.users = users;
    this.courses = courses;
    this.access = access;
    this.warnings = warnings;
    this.warningDb = warningDb;
    this.clock = clock;
  }

  @Transactional
  public Map<String, Object> start(long lesson) {
    long user = Current.actor().id();
    users.lockUser(user);
    Map<String, Object> l = found(courses.lesson(lesson));
    access.enrolled(user, id(l, "course_id"));
    Map<String, Object> prior = db.active(user);
    long now = clock.millis();
    if (prior != null && now - id(prior, "last_at_ms") <= 30000) {
      warnings.raise(
          user, id(l, "course_id"), str(prior, "id"), "MULTI_PLAY", "已有有效播放会话；尝试启动章节：" + lesson);
      return row("conflict", true, "message", "已有视频正在学习，请先关闭原播放会话");
    }
    db.stop(user);
    String session = UUID.randomUUID().toString();
    db.start(row("id", session, "user_id", user, "lesson_id", lesson, "last_at_ms", now));
    db.init(user, lesson);
    return row("sessionId", session, "heartbeatSeconds", 10, "nextSeq", 1);
  }

  @Transactional
  public Map<String, Object> heartbeat(
      String session, int seq, double position, String event, boolean visible, double rate) {
    long user = Current.actor().id();
    users.lockUser(user);
    Map<String, Object> s = found(db.session(session));
    Problem.require(id(s, "user_id") == user, 404, "会话不存在");
    Map<String, Object> l = found(courses.lesson(id(s, "lesson_id")));
    access.enrolled(user, id(l, "course_id"));
    Map<String, Object> duplicate = db.event(session, seq);
    if (duplicate != null) return row("duplicate", true, "event", duplicate);
    Problem.require(Boolean.TRUE.equals(s.get("active")), 409, "播放会话已失效");
    Problem.require(seq == id(s, "last_seq") + 1, 409, "上报序号不连续，请重试上一条事件");
    Problem.require(
        Double.isFinite(position)
            && position >= 0
            && position <= num(l, "duration_seconds")
            && Double.isFinite(rate)
            && rate > 0,
        400,
        "播放位置或倍速不合法");
    Problem.require(
        Set.of("PLAY", "HEARTBEAT", "SEEK", "PAUSE", "END").contains(event), 400, "事件类型不合法");
    long now = clock.millis();
    double previous = num(s, "last_position");
    PlaybackRules.Decision d =
        PlaybackRules.assess(
            Boolean.TRUE.equals(s.get("playing")),
            previous,
            position,
            now - id(s, "last_at_ms"),
            visible,
            rate,
            event);
    String kind = "SEEK".equals(event) && position > previous ? "FORWARD_SEEK" : event;
    if (d.seconds() > 0)
      db.segment(
          row(
              "user",
              user,
              "lesson",
              id(l, "id"),
              "session",
              session,
              "start",
              previous,
              "end",
              previous + d.seconds()));
    db.eventCreate(
        row(
            "session",
            session,
            "seq",
            seq,
            "type",
            kind,
            "position",
            position,
            "accepted",
            d.seconds(),
            "reason",
            d.reason()));
    if ("FORWARD_SEEK".equals(kind) && db.recentSeeks(session) >= 3)
      warnings.raise(
          user, id(l, "course_id"), session, "FREQUENT_SEEK", "60秒内至少3次向前跳播；关联会话与事件日志可查");
    if ("播放位置跳变".equals(d.reason()))
      warnings.raise(
          user,
          id(l, "course_id"),
          session,
          "POSITION_JUMP",
          "位置从" + previous + "变为" + position + "，上报序号" + seq);
    boolean playing =
        visible
            && rate == 1d
            && Set.of("PLAY", "HEARTBEAT").contains(event)
            && !"播放位置跳变".equals(d.reason());
    db.update(
        row(
            "id",
            session,
            "seq",
            seq,
            "position",
            position,
            "now",
            now,
            "playing",
            playing,
            "active",
            !"END".equals(event)));
    recalculate(user, id(l, "id"));
    return row(
        "acceptedSeconds",
        d.seconds(),
        "message",
        d.reason(),
        "nextSeq",
        seq + 1,
        "lessons",
        db.progressList(user, id(l, "course_id")));
  }

  public void recalculate(long user, long lesson) {
    Map<String, Object> l = found(courses.lesson(lesson));
    List<double[]> intervals =
        db.segments(user, lesson).stream()
            .map(r -> new double[] {num(r, "start_seconds"), num(r, "end_seconds")})
            .toList();
    double seconds = intervals.stream().mapToDouble(s -> s[1] - s[0]).sum(),
        covered = PlaybackRules.coverage(intervals),
        duration = num(l, "duration_seconds");
    db.init(user, lesson);
    db.progress(
        row(
            "user",
            user,
            "lesson",
            lesson,
            "seconds",
            seconds,
            "covered",
            covered,
            "done",
            covered + 1e-6 >= duration * num(l, "coverage_threshold")
                && seconds + 1e-6 >= duration * num(l, "time_threshold")));
    List<Map<String, Object>> progress = db.progressList(user, id(l, "course_id"));
    boolean done =
        !progress.isEmpty()
            && progress.stream().allMatch(r -> ((Number) r.get("completed")).intValue() == 1);
    courses.completion(user, id(l, "course_id"), done);
  }

  @Transactional
  public void stop() {
    users.lockUser(Current.actor().id());
    db.stop(Current.actor().id());
  }

  @Transactional
  public void invalidate(long warning, String note) {
    Current.actor().requireAdmin();
    Problem.require(note != null && note.length() >= 5, 400, "请说明调整原因");
    Map<String, Object> w = found(warningDb.get(warning));
    long user = id(w, "user_id");
    users.lockUser(user);
    w = found(warningDb.lock(warning));
    Problem.require(
        "INVESTIGATING".equals(w.get("status")) && w.get("session_id") != null,
        409,
        "仅可调整核实中、具有关联会话的预警");
    Map<String, Object> s = found(db.session(str(w, "session_id")));
    Problem.require(id(s, "user_id") == user, 409, "预警和会话归属不一致");
    long lesson = id(s, "lesson_id"), course = id(found(courses.lesson(lesson)), "course_id");
    String before = db.progressList(user, course).toString();
    db.stop(user);
    int count = db.invalidate(str(s, "id"));
    recalculate(user, lesson);
    db.revokeCertificates(user, course);
    String after = db.progressList(user, course).toString();
    String detail = note + "；失效区间数：" + count + "；前：" + before + "；后：" + after;
    if (detail.length() > 1800) detail = detail.substring(0, 1800);
    warningDb.action(
        row(
            "warning",
            warning,
            "actor",
            Current.actor().id(),
            "action",
            "INVALIDATE_SESSION",
            "note",
            detail,
            "before",
            "INVESTIGATING",
            "after",
            "INVESTIGATING"));
    users.audit(Current.actor().id(), "LEARNING_ADJUST", Long.toString(warning), detail);
  }
}
