package com.xuri.training.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class PlaybackRulesTest {
  @Test
  void forwardSeekNeverCounts() {
    assertEquals(0, PlaybackRules.assess(true, 120, 1799, 10000, true, 1, "SEEK").seconds());
  }

  @Test
  void hiddenPausedAndBufferedDoNotEarnTime() {
    assertEquals(0, PlaybackRules.assess(false, 0, 10, 10000, true, 1, "HEARTBEAT").seconds());
    assertEquals(0, PlaybackRules.assess(true, 0, 10, 10000, false, 1, "HEARTBEAT").seconds());
    assertEquals(0, PlaybackRules.assess(true, 10, 10, 10000, true, 1, "HEARTBEAT").seconds());
  }

  @Test
  void expiredHeartbeatDoesNotFillOfflineGap() {
    assertEquals(0, PlaybackRules.assess(true, 0, 120, 120000, true, 1, "HEARTBEAT").seconds());
  }

  @Test
  void forgedProgressAndSpeedAreRejected() {
    assertEquals(
        "播放位置跳变", PlaybackRules.assess(true, 0, 120, 10000, true, 1, "HEARTBEAT").reason());
    assertEquals(0, PlaybackRules.assess(true, 0, 20, 10000, true, 2, "HEARTBEAT").seconds());
  }

  @Test
  void normalPlaybackCountsAndCannotExceedWallTime() {
    assertEquals(10, PlaybackRules.assess(true, 0, 10, 10000, true, 1, "HEARTBEAT").seconds());
    assertEquals(10, PlaybackRules.assess(true, 0, 10.5, 10000, true, 1, "HEARTBEAT").seconds());
  }

  @Test
  void repeatedMinuteHasOneMinuteOfCoverage() {
    List<double[]> all = new ArrayList<>();
    for (int i = 0; i < 30; i++) all.add(new double[] {0, 60});
    assertEquals(60, PlaybackRules.coverage(all));
  }

  @Test
  void overlappingAndDisjointSegmentsMergeCorrectly() {
    assertEquals(
        50,
        PlaybackRules.coverage(
            List.of(new double[] {40, 60}, new double[] {0, 20}, new double[] {10, 30})));
    assertEquals(0, PlaybackRules.coverage(List.of()));
  }

  @Test
  void pauseCanCreditOnlyItsLastValidInterval() {
    assertEquals(7, PlaybackRules.assess(true, 10, 17, 7000, true, 1, "PAUSE").seconds());
    assertEquals(0, PlaybackRules.assess(true, 10, 0, 7000, true, 1, "HEARTBEAT").seconds());
  }
}
