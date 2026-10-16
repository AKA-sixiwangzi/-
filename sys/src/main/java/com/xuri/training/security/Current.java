package com.xuri.training.security;

public final class Current {
  private static final ThreadLocal<Actor> VALUE = new ThreadLocal<>();

  private Current() {}

  public static Actor actor() {
    Actor a = VALUE.get();
    if (a == null) throw new com.xuri.training.common.Problem(401, "请先登录");
    return a;
  }

  public static void set(Actor a) {
    VALUE.set(a);
  }

  public static void clear() {
    VALUE.remove();
  }
}
