package com.xuri.training.config;

import jakarta.servlet.*;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.servlet.support.AbstractAnnotationConfigDispatcherServletInitializer;

public class WebInitializer extends AbstractAnnotationConfigDispatcherServletInitializer {
  protected Class<?>[] getRootConfigClasses() {
    return new Class[] {AppConfig.class};
  }

  protected Class<?>[] getServletConfigClasses() {
    return null;
  }

  protected String[] getServletMappings() {
    return new String[] {"/"};
  }

  protected Filter[] getServletFilters() {
    return new Filter[] {new CharacterEncodingFilter("UTF-8", true)};
  }

  protected void customizeRegistration(ServletRegistration.Dynamic r) {
    r.setMultipartConfig(new MultipartConfigElement("", 524288000, 525336576, 1048576));
  }
}
