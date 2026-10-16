package com.xuri.training.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xuri.training.security.AuthInterceptor;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@EnableWebMvc
public class WebConfig implements WebMvcConfigurer {
  private final AuthInterceptor auth;
  private final ObjectMapper json;

  public WebConfig(AuthInterceptor auth, ObjectMapper json) {
    this.auth = auth;
    this.json = json;
  }

  public void addInterceptors(InterceptorRegistry r) {
    r.addInterceptor(auth)
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/auth/login", "/api/health", "/api/media/**");
  }

  public void extendMessageConverters(List<HttpMessageConverter<?>> cs) {
    cs.forEach(
        c -> {
          if (c instanceof MappingJackson2HttpMessageConverter j) j.setObjectMapper(json);
        });
  }
}
