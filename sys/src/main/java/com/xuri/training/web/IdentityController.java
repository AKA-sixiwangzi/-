package com.xuri.training.web;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.*;
import com.xuri.training.security.*;
import com.xuri.training.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class IdentityController {
  private final IdentityService service;
  private final IdentityMapper db;

  public IdentityController(IdentityService service, IdentityMapper db) {
    this.service = service;
    this.db = db;
  }

  @GetMapping("/health")
  public Object health() {
    return Map.of("status", "UP", "application", "旭日培训", "architecture", "SSM");
  }

  @PostMapping("/auth/login")
  public ResponseEntity<?> login(@Valid @RequestBody Requests.Login r) {
    Map<String, Object> result = service.login(r.username(), r.password());
    return ResponseEntity.status(
            result.containsKey("denied") ? ((Number) result.get("status")).intValue() : 200)
        .body(result);
  }

  @PostMapping("/auth/logout")
  public void logout(HttpServletRequest r) {
    db.revoke(AuthInterceptor.hash(AuthInterceptor.bearer(r)));
  }

  @PutMapping("/auth/password")
  public void password(@Valid @RequestBody Requests.Password r) {
    service.changePassword(r.oldPassword(), r.newPassword());
  }

  @GetMapping("/me")
  public Object me() {
    Actor a = Current.actor();
    List<String> workspaces = new ArrayList<>(List.of("LEARNING", "CONTENT_CREATION"));
    if (a.roles().contains("INSTRUCTOR")) workspaces.add("TEACHING");
    if (a.roles().contains("MANAGER")) workspaces.add("TEAM");
    if (a.admin()) workspaces.add("ADMINISTRATION");
    return row(
        "user",
        db.profile(a.id()),
        "roles",
        a.roles(),
        "workspaces",
        workspaces,
        "defaultWorkspace",
        "LEARNING",
        "theme",
        row("primary", "#003f98", "navy", "#00274c", "accent", "#ffcb0b"));
  }

  @GetMapping("/organization")
  public Object organization() {
    return row("departments", db.departments(), "positions", db.positions(), "grades", db.grades());
  }

  @GetMapping("/admin/users")
  public Object users(@RequestParam(defaultValue = "0") int offset) {
    Current.actor().requireAdmin();
    Problem.require(offset >= 0, 400, "偏移量不合法");
    return db.users(offset);
  }

  @PostMapping("/admin/users")
  public Object create(@Valid @RequestBody Requests.User r) {
    return service.create(
        r.username(), r.password(), r.displayName(), r.departmentId(), r.positionId(), r.gradeId());
  }

  @PutMapping("/admin/users/{id}/roles")
  public void role(@PathVariable long id, @Valid @RequestBody Requests.Role r) {
    service.role(id, r.role(), r.enabled());
  }

  @PutMapping("/admin/users/{id}")
  public void profile(@PathVariable long id, @Valid @RequestBody Requests.UserProfile r) {
    Current.actor().requireAdmin();
    com.xuri.training.common.Problem.require(
        db.profileUpdate(
                row(
                    "id",
                    id,
                    "display_name",
                    r.displayName(),
                    "department_id",
                    r.departmentId(),
                    "position_id",
                    r.positionId(),
                    "grade_id",
                    r.gradeId()))
            == 1,
        404,
        "用户不存在");
    db.audit(Current.actor().id(), "USER_PROFILE", Long.toString(id), "更新员工部门岗位职级");
  }

  @PutMapping("/admin/users/{id}/active")
  public void active(@PathVariable long id, @RequestBody Requests.Active r) {
    Current.actor().requireAdmin();
    Problem.require(id != Current.actor().id(), 409, "不能停用当前账号");
    Problem.require(db.active(id, r.active()) == 1, 404, "用户不存在");
    db.revokeAll(id);
    db.audit(Current.actor().id(), "USER_ACTIVE", Long.toString(id), Boolean.toString(r.active()));
  }

  @PostMapping("/admin/users/{id}/manager-scope")
  public void scope(@PathVariable long id, @Valid @RequestBody Requests.Scope r) {
    Current.actor().requireAdmin();
    found(db.profile(id));
    Problem.require(db.roles(id).contains("MANAGER"), 400, "请先授予部门主管角色");
    db.managerScope(id, r.departmentId());
    db.audit(
        Current.actor().id(), "MANAGER_SCOPE", Long.toString(id), Long.toString(r.departmentId()));
  }

  @DeleteMapping("/admin/users/{id}/manager-scope/{departmentId}")
  public void revokeScope(@PathVariable long id, @PathVariable long departmentId) {
    Current.actor().requireAdmin();
    db.revokeScope(id, departmentId);
    db.audit(
        Current.actor().id(),
        "MANAGER_SCOPE_REVOKE",
        Long.toString(id),
        Long.toString(departmentId));
  }

  @PostMapping("/admin/departments")
  public Object department(@Valid @RequestBody Requests.Name r) {
    Current.actor().requireAdmin();
    Map<String, Object> v = row("name", r.name(), "parent_id", r.parentId());
    db.department(v);
    return v;
  }

  @PostMapping("/admin/positions")
  public Object position(@Valid @RequestBody Requests.Name r) {
    Current.actor().requireAdmin();
    Map<String, Object> v = row("name", r.name());
    db.position(v);
    return v;
  }

  @PostMapping("/admin/grades")
  public Object grade(@Valid @RequestBody Requests.Name r) {
    Current.actor().requireAdmin();
    Map<String, Object> v = row("name", r.name(), "sort_order", r.sortOrder());
    db.grade(v);
    return v;
  }

  @GetMapping("/admin/audits")
  public Object audits() {
    Current.actor().requireAdmin();
    return db.audits();
  }
}
