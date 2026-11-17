package com.xuri.training.service;

import static com.xuri.training.common.Rows.*;

import com.xuri.training.common.*;
import com.xuri.training.mapper.IdentityMapper;
import com.xuri.training.security.*;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
  private final IdentityMapper db;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
  private final SecureRandom random = new SecureRandom();

  private void passwordLength(String s) {
    Problem.require(
        s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72, 400, "密码UTF-8编码不能超过72字节");
  }

  public IdentityService(IdentityMapper db) {
    this.db = db;
  }

  // 返回失败结果而非在事务内抛错，保证登录失败计数提交。
  @Transactional
  public Map<String, Object> login(String name, String password) {
    passwordLength(password);
    if (db.loginGuard(name) == null) db.initGuard(name);
    if (Objects.equals(db.blocked(name), 1))
      return row("denied", true, "status", 429, "message", "尝试过多，请15分钟后重试");
    Map<String, Object> u = db.byName(name);
    if (u == null
        || !Boolean.TRUE.equals(u.get("active"))
        || !encoder.matches(password, str(u, "password_hash"))) {
      db.failedLogin(name);
      return row("denied", true, "status", 401, "message", "账号或密码错误");
    }
    db.resetGuard(name);
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    long id = id(u, "id");
    db.token(AuthInterceptor.hash(token), id);
    return row("token", token, "expiresIn", 28800, "user", db.profile(id), "roles", db.roles(id));
  }

  @Transactional
  public Map<String, Object> create(
      String name, String password, String display, Long dept, Long position, Long grade) {
    Current.actor().requireAdmin();
    passwordLength(password);
    Map<String, Object> r =
        row(
            "username",
            name,
            "password_hash",
            encoder.encode(password),
            "display_name",
            display,
            "department_id",
            dept,
            "position_id",
            position,
            "grade_id",
            grade);
    db.create(r);
    db.grant(id(r, "id"), "EMPLOYEE");
    db.audit(Current.actor().id(), "USER_CREATE", r.get("id").toString(), name);
    r.remove("password_hash");
    return r;
  }

  @Transactional
  public Map<String, Object> employee(String display, Long dept, Long position, Long grade) {
    Current.actor().requireAdmin();
    Problem.require(display != null && !display.isBlank() && display.length() <= 100, 400, "员工姓名不合法");
    String username = availableEmployeeUsername();
    String password = temporaryPassword();
    Map<String, Object> employee =
        row(
            "username", username,
            "password_hash", encoder.encode(password),
            "display_name", display,
            "department_id", dept,
            "position_id", position,
            "grade_id", grade);
    // Employee and login account share one app_user row and one transaction.
    db.createEmployee(employee);
    long employeeId = id(employee, "id");
    db.grant(employeeId, "EMPLOYEE");
    db.audit(Current.actor().id(), "EMPLOYEE_CREATE", Long.toString(employeeId), username);
    employee.remove("password_hash");
    employee.put("active", true);
    employee.put("must_change_password", true);
    return row(
        "user", employee,
        "account", row("username", username, "temporaryPassword", password, "requiresPasswordChange", true));
  }

  private String availableEmployeeUsername() {
    for (int attempt = 0; attempt < 10; attempt++) {
      byte[] suffix = new byte[12];
      random.nextBytes(suffix);
      String username = "EMP" + HexFormat.of().withUpperCase().formatHex(suffix);
      if (db.byName(username) == null) return username;
    }
    throw new Problem(503, "暂时无法生成员工账号，请重试");
  }

  private String temporaryPassword() {
    String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    String lower = "abcdefghijkmnopqrstuvwxyz";
    String digits = "23456789";
    String alphabet = upper + lower + digits;
    char[] password = new char[20];
    password[0] = upper.charAt(random.nextInt(upper.length()));
    password[1] = lower.charAt(random.nextInt(lower.length()));
    password[2] = digits.charAt(random.nextInt(digits.length()));
    for (int i = 3; i < password.length; i++) password[i] = alphabet.charAt(random.nextInt(alphabet.length()));
    for (int i = password.length - 1; i > 0; i--) {
      int other = random.nextInt(i + 1);
      char saved = password[i];
      password[i] = password[other];
      password[other] = saved;
    }
    return new String(password);
  }

  @Transactional
  public void role(long id, String role, boolean grant) {
    Current.actor().requireAdmin();
    Problem.require(Set.of("ADMIN", "MANAGER", "INSTRUCTOR").contains(role), 400, "角色不合法");
    found(db.profile(id));
    Problem.require(id != Current.actor().id(), 409, "不能更改自己的管理权限");
    if (grant) db.grant(id, role);
    else db.ungrant(id, role);
    db.revokeAll(id);
    db.audit(Current.actor().id(), "ROLE_CHANGE", Long.toString(id), role + ":" + grant);
  }

  @Transactional
  public void changePassword(String old, String next) {
    Actor a = Current.actor();
    Problem.require(next != null && next.length() >= 12, 400, "新密码至少12位");
    passwordLength(old);
    passwordLength(next);
    Map<String, Object> u = found(db.lockUser(a.id()));
    Problem.require(encoder.matches(old, str(u, "password_hash")), 400, "原密码错误");
    Problem.require(!old.equals(next), 400, "新密码不能与原密码相同");
    db.password(a.id(), encoder.encode(next));
    db.revokeAll(a.id());
  }

  @Transactional
  public void bootstrap(String user, String password) {
    if (user.isBlank() || password.isBlank()) return;
    Problem.require(password.length() >= 12, 500, "初始管理员密码至少12位");
    passwordLength(password);
    if (db.byName(user) == null) {
      Map<String, Object> r =
          row("username", user, "password_hash", encoder.encode(password), "display_name", "系统管理员");
      db.create(r);
      db.grant(id(r, "id"), "EMPLOYEE");
      db.grant(id(r, "id"), "ADMIN");
      db.audit(null, "BOOTSTRAP", user, "初始化管理员");
    }
  }
}
