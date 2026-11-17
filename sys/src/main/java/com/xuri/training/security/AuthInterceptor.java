package com.xuri.training.security;

import com.xuri.training.common.Problem;
import com.xuri.training.mapper.IdentityMapper;
import jakarta.servlet.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

@Component
public class AuthInterceptor implements AsyncHandlerInterceptor {
  private final IdentityMapper users;

  public AuthInterceptor(IdentityMapper users) {
    this.users = users;
  }

  public static String hash(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String bearer(HttpServletRequest r) {
    String h = r.getHeader("Authorization");
    Problem.require(h != null && h.startsWith("Bearer ") && h.length() < 200, 401, "请先登录");
    return h.substring(7);
  }

  public boolean preHandle(HttpServletRequest r, HttpServletResponse s, Object h) {
    Current.clear();
    Long id = users.authenticate(hash(bearer(r)));
    Problem.require(id != null, 401, "登录已失效");
    String path = r.getRequestURI().substring(r.getContextPath().length());
    boolean passwordAction =
        "GET".equals(r.getMethod()) && "/api/me".equals(path)
            || "PUT".equals(r.getMethod()) && "/api/auth/password".equals(path)
            || "POST".equals(r.getMethod()) && "/api/auth/logout".equals(path);
    Problem.require(
        !Boolean.TRUE.equals(users.mustChangePassword(id)) || passwordAction,
        403,
        "首次登录请先修改初始密码");
    Current.set(new Actor(id, users.roles(id)));
    return true;
  }

  public void afterCompletion(HttpServletRequest r, HttpServletResponse s, Object h, Exception e) {
    Current.clear();
  }

  @Override
  public void afterConcurrentHandlingStarted(
      HttpServletRequest r, HttpServletResponse s, Object h) {
    Current.clear();
  }
}
