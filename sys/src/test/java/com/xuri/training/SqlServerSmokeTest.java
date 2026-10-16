package com.xuri.training;

import static org.junit.jupiter.api.Assertions.*;

import com.xuri.training.mapper.*;
import java.sql.*;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "TRAINING_TEST_DB_URL", matches = ".+")
class SqlServerSmokeTest {
  @Test
  void mappingsExecuteAgainstInitializedSqlServer() throws Exception {
    var source =
        new UnpooledDataSource(
            "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            System.getenv("TRAINING_TEST_DB_URL"),
            System.getenv("TRAINING_TEST_DB_USER"),
            System.getenv("TRAINING_TEST_DB_PASSWORD"));
    try (Connection c = source.getConnection()) {
      assertTrue(c.getMetaData().getDatabaseProductName().contains("Microsoft SQL Server"));
      try (var s = c.createStatement();
          var r =
              s.executeQuery(
                  "SELECT COUNT(*) FROM sys.tables WHERE name"
                      + " IN('app_user','course','learning_path','training_plan','watch_progress','warning','exam','paper','certificate','media_ticket','plan_exam')")) {
        assertTrue(r.next());
        assertEquals(11, r.getInt(1));
      }
    }
    Configuration config =
        new Configuration(new Environment("sqlserver-smoke", new JdbcTransactionFactory(), source));
    for (Class<?> m :
        new Class<?>[] {
          IdentityMapper.class,
          CourseMapper.class,
          LearningMapper.class,
          WarningMapper.class,
          ProgramMapper.class,
          ExamMapper.class,
          AiMapper.class,
          ResultMapper.class
        }) config.addMapper(m);
    try (SqlSession s = new SqlSessionFactoryBuilder().build(config).openSession()) {
      s.getMapper(IdentityMapper.class).departments();
      s.getMapper(IdentityMapper.class).users(0);
      s.getMapper(CourseMapper.class).catalog(0, true, 0, "", "");
      s.getMapper(CourseMapper.class).learners(-1);
      s.getMapper(LearningMapper.class).progressList(-1, -1);
      s.getMapper(WarningMapper.class).list(true, 0);
      s.getMapper(ProgramMapper.class).paths(true, 0);
      s.getMapper(ProgramMapper.class).reminders();
      s.getMapper(ExamMapper.class).papers(0, true);
      s.getMapper(ExamMapper.class).pending(0, true);
      s.getMapper(ExamMapper.class).wrongAnswers(-1);
      s.getMapper(ResultMapper.class).personal(-1);
      s.getMapper(ResultMapper.class).company(true, 0);
      s.getMapper(ResultMapper.class).planSummary();
    }
  }
}
