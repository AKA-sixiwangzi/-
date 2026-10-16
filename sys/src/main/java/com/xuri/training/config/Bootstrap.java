package com.xuri.training.config;

import com.xuri.training.service.IdentityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.*;
import org.springframework.stereotype.Component;

@Component
public class Bootstrap {
  private final IdentityService users;
  private final String name, password;

  public Bootstrap(
      IdentityService users,
      @Value("${bootstrap.user}") String name,
      @Value("${bootstrap.password}") String password) {
    this.users = users;
    this.name = name;
    this.password = password;
  }

  @EventListener(ContextRefreshedEvent.class)
  public void start() {
    if (!name.isBlank() && !password.isBlank()) users.bootstrap(name, password);
  }
}
