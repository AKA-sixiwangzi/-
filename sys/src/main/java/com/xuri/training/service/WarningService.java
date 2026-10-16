package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarningService {
  private final WarningMapper db;
  private final IdentityMapper users;

  public WarningService(WarningMapper db, IdentityMapper users) {
    this.db = db;
    this.users = users;
  }

  public void raise(long user, long course, String session, String type, String evidence) {
    Long prior = db.recent(user, type);
    if (prior != null) {
      db.repeat(prior);
      return;
    }
    Map<String, Object> r =
        row(
            "user",
            user,
            "course",
            course,
            "session",
            session,
            "type",
            type,
            "severity",
            "HIGH",
            "evidence",
            evidence);
    db.create(r);
    for (long admin : users.admins())
      db.notify(admin, "学习异常预警 #" + r.get("id"), type + "；员工ID：" + user + "，请前往预警中心核实。");
  }

  public Map<String, Object> detail(long id) {
    Map<String, Object> w = found(db.get(id));
    Problem.require(
        Current.actor().admin() || id(w, "user_id") == Current.actor().id(), 403, "无权查看该预警");
    return row("warning", w, "actions", db.actions(id));
  }

  @Transactional
  public void action(long id, String action, String note) {
    Map<String, Object> w = found(db.lock(id));
    Actor a = Current.actor();
    String before = str(w, "status"), after;
    if ("APPEAL".equals(action)) {
      Problem.require(id(w, "user_id") == a.id(), 403, "只能申诉自己的记录");
      Problem.require("CLOSED".equals(before), 409, "处理结束后可提出申诉");
      after = "INVESTIGATING";
    } else {
      a.requireAdmin();
      after =
          switch (action) {
            case "INVESTIGATE" -> {
              Problem.require("OPEN".equals(before), 409, "状态已变化");
              yield "INVESTIGATING";
            }
            case "WARN", "RELEARN", "FALSE_POSITIVE" -> {
              Problem.require("INVESTIGATING".equals(before), 409, "请先核实预警");
              yield "REVIEW";
            }
            case "CLOSE" -> {
              Problem.require("REVIEW".equals(before), 409, "请先完成处理");
              Problem.require(
                  w.get("handler_id") != null && id(w, "handler_id") != a.id(),
                  409,
                  "须由另一名管理员复核关闭");
              yield "CLOSED";
            }
            case "REOPEN" -> {
              Problem.require("CLOSED".equals(before), 409, "记录尚未关闭");
              yield "INVESTIGATING";
            }
            default -> throw new Problem(400, "不支持的处理动作");
          };
    }
    db.action(
        row(
            "warning", id, "actor", a.id(), "action", action, "note", note, "before", before,
            "after", after));
    db.state(id, after, a.id());
    db.notify(id(w, "user_id"), "预警处理更新 #" + id, note);
    users.audit(a.id(), "WARNING_" + action, Long.toString(id), note);
  }
}
