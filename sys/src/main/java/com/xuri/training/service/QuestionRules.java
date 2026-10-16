package com.xuri.training.service;

import com.xuri.training.common.Problem;
import java.util.*;

public final class QuestionRules {
  private QuestionRules() {}

  public static Set<String> choices(String answer) {
    Set<String> r = new TreeSet<>();
    for (String s : answer.toUpperCase(Locale.ROOT).split(",")) {
      String t = s.trim();
      if (!t.isEmpty()) r.add(t);
    }
    return r;
  }

  public static void validate(String type, Map<String, String> options, String answer) {
    Problem.require(
        Set.of("SINGLE", "MULTIPLE", "TRUE_FALSE", "TEXT").contains(type), 400, "题型不合法");
    Problem.require(
        answer != null && !answer.isBlank() && answer.length() <= 2000, 400, "答案不能为空或过长");
    if ("TEXT".equals(type)) return;
    Set<String> a = choices(answer);
    Problem.require(
        !options.isEmpty()
            && options.size() <= 10
            && options.keySet().stream().allMatch(k -> k.matches("[A-J]"))
            && options.values().stream()
                .allMatch(v -> v != null && !v.isBlank() && v.length() <= 1000),
        400,
        "选项不合法");
    Problem.require(!a.isEmpty() && options.keySet().containsAll(a), 400, "答案必须对应已有选项");
    if (!"MULTIPLE".equals(type)) Problem.require(a.size() == 1, 400, "单选或判断题只能有一个答案");
    if ("TRUE_FALSE".equals(type)) Problem.require(options.size() == 2, 400, "判断题必须有两个选项");
  }

  public static boolean correct(String expected, String actual) {
    return choices(expected).equals(choices(actual));
  }
}
