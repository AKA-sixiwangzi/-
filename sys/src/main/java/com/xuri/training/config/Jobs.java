package com.xuri.training.config;

import com.xuri.training.mapper.*;
import com.xuri.training.service.ExamService;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Jobs {
  private final ExamMapper exams;
  private final ExamService service;
  private final IdentityMapper users;
  private final ProgramMapper plans;
  private final WarningMapper messages;
  private final Clock clock;

  public Jobs(
      ExamMapper exams,
      ExamService service,
      IdentityMapper users,
      ProgramMapper plans,
      WarningMapper messages,
      Clock clock) {
    this.exams = exams;
    this.service = service;
    this.users = users;
    this.plans = plans;
    this.messages = messages;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 15000)
  public void expire() {
    for (String id : exams.expired(clock.millis()))
      try {
        service.finish(id);
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(Jobs.class).error("考试自动交卷失败 {}", id, e);
      }
  }

  @Scheduled(fixedDelay = 3600000)
  public void maintenance() {
    users.expireTokens();
    users.expireTickets();
    for (var p : plans.reminders())
      messages.notify(
          com.xuri.training.common.Rows.id(p, "user_id"),
          "培训到期提醒 #" + p.get("id"),
          p.get("title").toString());
  }
}
