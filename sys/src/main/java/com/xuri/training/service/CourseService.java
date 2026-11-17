package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import java.net.URI;
import java.net.URISyntaxException;
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
    return material(course, title, content, asset, null, null, false);
  }

  @Transactional
  public Map<String, Object> material(
      long course, String title, String content, String asset, String type, String url, Boolean required) {
    editable(course);
    assetOwned(asset);
    Problem.require(title != null && !title.isBlank() && title.length() <= 200, 400, "请填写资料标题");
    Map<String, Object> file = asset == null ? null : found(db.asset(asset));
    String kind = type == null ? inferredType(file) : type;
    String body = content == null ? "" : content;
    String link = url == null || url.isBlank() ? null : url.trim();
    validateMaterial(kind, body, file, link);
    Map<String, Object> r =
        row("course_id", course, "title", title, "content", body, "asset_id", asset,
            "material_type", kind, "external_url", link, "required", Boolean.TRUE.equals(required));
    db.materialCreate(r);
    return r;
  }

  private String inferredType(Map<String, Object> file) {
    if (file == null) return "TEXT";
    return switch (str(file, "content_type")) {
      case "application/pdf" -> "PDF";
      case "application/vnd.ms-powerpoint",
          "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPT";
      default -> "TEXT";
    };
  }

  private void validateMaterial(String kind, String content, Map<String, Object> file, String url) {
    Problem.require(Set.of("TEXT", "PDF", "PPT", "LINK").contains(kind), 400, "资料类型不支持");
    Problem.require(content.length() <= 200000, 400, "资料正文超过长度限制");
    String mime = file == null ? "" : str(file, "content_type");
    if ("LINK".equals(kind)) {
      Problem.require(file == null, 400, "链接资料不能同时关联上传文件");
      Problem.require(validLink(url), 400, "链接必须是没有账号密码且带有效主机名的HTTP或HTTPS地址");
      return;
    }
    Problem.require(url == null, 400, "仅链接资料可以设置外部地址");
    switch (kind) {
      case "PDF" -> Problem.require("application/pdf".equals(mime), 400, "PDF资料必须关联PDF文件");
      case "PPT" -> Problem.require(
          Set.of("application/vnd.ms-powerpoint",
              "application/vnd.openxmlformats-officedocument.presentationml.presentation").contains(mime),
          400, "PPT资料必须关联PPT或PPTX文件");
      default -> {
        Problem.require(file == null || "text/plain".equals(mime), 400, "文字资料只能关联TXT文件");
        Problem.require(!content.isBlank() || file != null, 400, "文字资料需要正文或TXT附件");
      }
    }
  }

  private boolean validLink(String value) {
    if (value == null || value.length() > 2048) return false;
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme();
      return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          && uri.getHost() != null && !uri.getHost().isBlank()
          && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() <= 65535);
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private boolean flag(Object value) {
    return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
  }

  private Map<String, Object> learningState(long user, long course) {
    List<Map<String, Object>> videos = db.learningVideos(user, course);
    List<Map<String, Object>> materials = db.learningMaterials(user, course);
    long videoTotal = videos.size();
    long videoCompleted = videos.stream().filter(v -> flag(v.get("completed"))).count();
    long materialTotal = materials.stream().filter(m -> flag(m.get("required"))).count();
    long materialCompleted = materials.stream()
        .filter(m -> flag(m.get("required")) && flag(m.get("acknowledged"))).count();
    long total = videoTotal + materialTotal, complete = videoCompleted + materialCompleted;
    return row("completed", total > 0 && complete == total,
        "videoCompleted", videoCompleted, "videoTotal", videoTotal,
        "materialCompleted", materialCompleted, "materialTotal", materialTotal,
        "materials", materials, "percent", total == 0 ? 0d : Math.round(10000d * complete / total) / 100d);
  }

  public Map<String, Object> learningStatus(long course) {
    readable(course);
    return learningState(Current.actor().id(), course);
  }

  // Called while the learner is locked by a heartbeat or explicit material confirmation.
  public void recalculateCompletion(long user, long course) {
    db.completion(user, course, Boolean.TRUE.equals(learningState(user, course).get("completed")));
  }

  @Transactional
  public Map<String, Object> acknowledgeMaterial(long material) {
    long user = Current.actor().id();
    users.lockUser(user);
    Map<String, Object> resource = found(db.material(material));
    long course = id(resource, "course_id");
    enrolled(user, course);
    db.acknowledge(user, material);
    Map<String, Object> status = learningState(user, course);
    db.completion(user, course, Boolean.TRUE.equals(status.get("completed")));
    return status;
  }

  @Transactional
  public void submit(long id) {
    editable(id);
    List<Map<String, Object>> ls = db.lessons(id);
    List<Map<String, Object>> required = db.materials(id).stream()
        .filter(material -> flag(material.get("required"))).toList();
    Problem.require(
        (!ls.isEmpty() || !required.isEmpty())
            && ls.stream().allMatch(l -> l.get("video_asset_id") != null),
        400,
        "请配置有效视频章节，或添加至少一份必读资料");
    for (Map<String, Object> material : required) {
      String asset = material.get("asset_id") == null ? null : str(material, "asset_id");
      Map<String, Object> file = asset == null ? null : found(db.asset(asset));
      String type = material.get("material_type") == null ? inferredType(file) : str(material, "material_type");
      validateMaterial(type, str(material, "content"), file,
          material.get("external_url") == null ? null : str(material, "external_url"));
    }
    db.status(id, "PENDING", null, "");
    db.reviewLog(id, Current.actor().id(), "SUBMIT", "提交审核");
    for (long admin : users.admins())
      users.notifyUser(admin, "课程待审核 #" + id, "员工已提交课程，请核验课程内容、资料及视频章节时长。");
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
