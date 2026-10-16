package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {
  private final CourseMapper db;
  private final IdentityMapper users;

  public CourseService(CourseMapper db, IdentityMapper users) {
    this.db = db;
    this.users = users;
  }

  public Map<String, Object> manage(long id) {
    Map<String, Object> c = found(db.course(id));
    Actor a = Current.actor();
    Problem.require(a.admin() || id(c, "owner_id") == a.id(), 403, "只能管理自己的课程");
    return c;
  }

  public Map<String, Object> readable(long id) {
    Map<String, Object> c = found(db.course(id));
    Actor a = Current.actor();
    Problem.require(
        a.admin()
            || id(c, "owner_id") == a.id()
            || "PUBLISHED".equals(c.get("status")) && db.enrolled(a.id(), id) > 0,
        403,
        "课程未分配或尚未发布");
    return c;
  }

  public void enrolled(long user, long course) {
    Problem.require(db.enrolled(user, course) > 0, 403, "请先领取学习任务");
    Problem.require("PUBLISHED".equals(found(db.course(course)).get("status")), 409, "课程暂不可学习");
  }

  @Transactional
  public Map<String, Object> create(String title, String description, String category) {
    Map<String, Object> c =
        row(
            "owner_id",
            Current.actor().id(),
            "title",
            title,
            "description",
            description,
            "category",
            category);
    db.create(c);
    users.audit(Current.actor().id(), "COURSE_CREATE", c.get("id").toString(), title);
    return c;
  }

  private void editable(long id) {
    Map<String, Object> c = found(db.lockCourse(id));
    manage(id);
    Problem.require(
        Set.of("DRAFT", "REJECTED").contains(str(c, "status")), 409, "只有草稿或驳回的课程可编辑；已发布课程请新建修订版");
  }

  @Transactional
  public void update(long id, String title, String description, String category) {
    editable(id);
    db.update(row("id", id, "title", title, "description", description, "category", category));
  }

  @Transactional
  public Map<String, Object> lesson(
      long course,
      String title,
      int order,
      int seconds,
      String asset,
      double coverage,
      double time) {
    editable(course);
    assetOwned(asset);
    Problem.require(
        asset != null && str(found(db.asset(asset)), "content_type").startsWith("video/"),
        400,
        "章节必须关联视频文件");
    Map<String, Object> l =
        row(
            "course_id",
            course,
            "title",
            title,
            "sort_order",
            order,
            "duration_seconds",
            seconds,
            "video_asset_id",
            asset,
            "coverage_threshold",
            coverage,
            "time_threshold",
            time);
    db.lessonCreate(l);
    return l;
  }

  public void assetOwned(String asset) {
    if (asset == null) return;
    Map<String, Object> f = found(db.asset(asset));
    Problem.require(
        id(f, "owner_id") == Current.actor().id() || Current.actor().admin(), 403, "附件不属于当前用户");
  }

  @Transactional
  public Map<String, Object> material(long course, String title, String content, String asset) {
    editable(course);
    assetOwned(asset);
    Map<String, Object> r =
        row("course_id", course, "title", title, "content", content, "asset_id", asset);
    db.materialCreate(r);
    return r;
  }

  @Transactional
  public void submit(long id) {
    editable(id);
    List<Map<String, Object>> ls = db.lessons(id);
    Problem.require(
        !ls.isEmpty() && ls.stream().allMatch(l -> l.get("video_asset_id") != null),
        400,
        "请先上传课程视频并配置章节");
    db.status(id, "PENDING", null, "");
    db.reviewLog(id, Current.actor().id(), "SUBMIT", "提交审核");
    for (long admin : users.admins())
      users.notifyUser(admin, "课程待审核 #" + id, "员工已提交课程，请在审核中心核验内容和视频时长。");
  }

  @Transactional
  public void review(long id, boolean approve, String note) {
    Current.actor().requireAdmin();
    Map<String, Object> c = found(db.lockCourse(id));
    Problem.require(id(c, "owner_id") != Current.actor().id(), 409, "不能审核自己的课程");
    Problem.require("PENDING".equals(c.get("status")), 409, "课程不处于待审核状态");
    db.status(id, approve ? "PUBLISHED" : "REJECTED", Current.actor().id(), note);
    db.reviewLog(id, Current.actor().id(), approve ? "APPROVE" : "REJECT", note);
    if (approve) users.grant(id(c, "owner_id"), "INSTRUCTOR");
    users.notifyUser(id(c, "owner_id"), approve ? "课程已审核发布" : "课程审核未通过", note);
    users.audit(Current.actor().id(), "COURSE_REVIEW", Long.toString(id), note);
  }

  @Transactional
  public void enroll(long course) {
    readable(course);
    Problem.require("PUBLISHED".equals(db.course(course).get("status")), 409, "课程未发布");
    users.lockUser(Current.actor().id());
    db.enroll(Current.actor().id(), course);
  }

  public Map<String, Object> detail(long id) {
    Map<String, Object> c = readable(id);
    return row("course", c, "lessons", db.lessons(id), "materials", db.materials(id));
  }

  @Transactional
  public void archive(long id) {
    manage(id);
    Map<String, Object> c = found(db.lockCourse(id));
    Problem.require("PUBLISHED".equals(c.get("status")), 409, "仅已发布课程可下架");
    db.status(id, "ARCHIVED", null, "课程已下架");
    db.reviewLog(id, Current.actor().id(), "ARCHIVE", "课程已下架，历史学习记录保留");
    users.audit(Current.actor().id(), "COURSE_ARCHIVE", Long.toString(id), "下架课程");
  }

  @Transactional
  public void restore(long id) {
    Current.actor().requireAdmin();
    Map<String, Object> c = found(db.lockCourse(id));
    Problem.require("ARCHIVED".equals(c.get("status")), 409, "课程不处于下架状态");
    db.status(id, "PUBLISHED", Current.actor().id(), "恢复已审核版本");
    db.reviewLog(id, Current.actor().id(), "RESTORE", "恢复已审核版本");
    users.audit(Current.actor().id(), "COURSE_RESTORE", Long.toString(id), "恢复课程");
  }
}
