package com.xuri.training.common;

import java.util.*;

public final class Rows {
  private Rows() {}

  public static Map<String, Object> row(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
    return m;
  }

  public static long id(Map<String, Object> r, String k) {
    return ((Number) r.get(k)).longValue();
  }

  public static double num(Map<String, Object> r, String k) {
    return r.get(k) == null ? 0 : ((Number) r.get(k)).doubleValue();
  }

  public static String str(Map<String, Object> r, String k) {
    return Objects.toString(r.get(k), "");
  }

  public static Map<String, Object> found(Map<String, Object> r) {
    Problem.require(r != null, 404, "记录不存在");
    return r;
  }
}
