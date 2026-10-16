package com.xuri.training.common;

public class Problem extends RuntimeException {
  public final int status;

  public Problem(int status, String message) {
    super(message);
    this.status = status;
  }

  public static void require(boolean ok, int status, String message) {
    if (!ok) throw new Problem(status, message);
  }
}
