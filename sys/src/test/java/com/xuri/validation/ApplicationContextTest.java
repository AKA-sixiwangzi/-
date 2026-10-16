package com.xuri.validation;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.xuri.training.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

class ApplicationContextTest {
  @Configuration
  static class NoExternalJobs {
    @Bean
    TaskScheduler taskScheduler() {
      return mock(TaskScheduler.class, RETURNS_DEEP_STUBS);
    }
  }

  @Test
  void productionSsmContextStartsAndPublicHealthRouteWorks() throws Exception {
    try (var context = new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "test",
                  Map.of(
                      "TRAINING_DB_URL",
                      "jdbc:sqlserver://localhost:9;databaseName=not_connected",
                      "TRAINING_DB_USER",
                      "unused",
                      "TRAINING_DB_PASSWORD",
                      "unused",
                      "TRAINING_ADMIN_USER",
                      "",
                      "TRAINING_ADMIN_PASSWORD",
                      "")));
      context.register(AppConfig.class, NoExternalJobs.class);
      context.refresh();
      MockMvcBuilders.webAppContextSetup(context)
          .build()
          .perform(get("/api/health"))
          .andExpect(status().isOk())
          .andExpect(content().string(org.hamcrest.Matchers.containsString("SSM")));
    }
  }
}
