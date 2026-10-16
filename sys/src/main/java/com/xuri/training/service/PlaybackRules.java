package com.xuri.training.service;

import java.util.*;

public final class PlaybackRules {
  private PlaybackRules() {}

  public record Decision(double seconds, String reason) {}

  public static Decision assess(
      boolean wasPlaying,
      double previous,
      double position,
      long elapsedMs,
      boolean visible,
      double rate,
      String event) {
    if ("SEEK".equals(event)) return new Decision(0, "拖动不计时");
    if (!wasPlaying || !visible || rate != 1d) return new Decision(0, "当前状态不计时");
    if (elapsedMs <= 0 || elapsedMs > 30000) return new Decision(0, "心跳超时或时间异常");
    double wall = elapsedMs / 1000d, delta = position - previous;
    if (delta < 0 || delta > wall * 1.05 + .25) return new Decision(0, "播放位置跳变");
    return new Decision(Math.min(delta, wall), "有效播放");
  }

  public static double coverage(List<double[]> segments) {
    List<double[]> sorted = new ArrayList<>(segments);
    sorted.sort(Comparator.comparingDouble(a -> a[0]));
    double total = 0, start = -1, end = -1;
    for (double[] s : sorted) {
      if (start < 0) {
        start = s[0];
        end = s[1];
      } else if (s[0] <= end) {
        end = Math.max(end, s[1]);
      } else {
        total += end - start;
        start = s[0];
        end = s[1];
      }
    }
    return total + (start < 0 ? 0 : end - start);
  }
}
