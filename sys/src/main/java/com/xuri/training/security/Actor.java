package com.xuri.training.security;

import com.xuri.training.common.Problem;
import java.util.Set;

public record Actor(long id, Set<String> roles) {
  public boolean admin() {
    return roles.contains("ADMIN");
  }

  public void requireAdmin() {
    Problem.require(admin(), 403, "需要管理员权限");
  }
}
