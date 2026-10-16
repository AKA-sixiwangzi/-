package com.xuri.training.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

@Configuration
@ComponentScan("com.xuri.training")
@MapperScan("com.xuri.training.mapper")
@PropertySource("classpath:application.properties")
@EnableTransactionManagement
@EnableScheduling
public class AppConfig {
  @Bean
  static PropertySourcesPlaceholderConfigurer properties() {
    return new PropertySourcesPlaceholderConfigurer();
  }

  @Bean(destroyMethod = "close")
  DataSource dataSource(
      @Value("${db.url}") String url,
      @Value("${db.user}") String user,
      @Value("${db.password}") String password) {
    HikariDataSource d = new HikariDataSource();
    d.setJdbcUrl(url);
    d.setUsername(user);
    d.setPassword(password);
    d.setMaximumPoolSize(12);
    d.setMinimumIdle(1);
    d.setConnectionInitSql(
        "SET ANSI_NULLS ON; SET QUOTED_IDENTIFIER ON; SET ANSI_PADDING ON; SET ANSI_WARNINGS ON;"
            + " SET CONCAT_NULL_YIELDS_NULL ON; SET ARITHABORT ON; SET NUMERIC_ROUNDABORT OFF;");
    return d;
  }

  @Bean
  SqlSessionFactory sqlSessionFactory(DataSource d) throws Exception {
    SqlSessionFactoryBean f = new SqlSessionFactoryBean();
    f.setDataSource(d);
    org.apache.ibatis.session.Configuration c = new org.apache.ibatis.session.Configuration();
    c.setCallSettersOnNulls(true);
    c.setJdbcTypeForNull(org.apache.ibatis.type.JdbcType.NULL);
    c.getTypeHandlerRegistry().register(java.sql.Timestamp.class, UtcTimestampTypeHandler.class);
    f.setConfiguration(c);
    return f.getObject();
  }

  @Bean
  DataSourceTransactionManager transactionManager(DataSource d) {
    return new DataSourceTransactionManager(d);
  }

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  StandardServletMultipartResolver multipartResolver() {
    return new StandardServletMultipartResolver();
  }
}
