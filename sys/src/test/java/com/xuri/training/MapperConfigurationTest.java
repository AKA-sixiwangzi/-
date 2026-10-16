package com.xuri.training;

import static org.junit.jupiter.api.Assertions.*;

import com.xuri.training.mapper.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class MapperConfigurationTest {
  @Test
  void allMapperStatementsAndParametersParse() {
    Configuration c = new Configuration();
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
        }) c.addMapper(m);
    assertTrue(c.getMappedStatementNames().size() > 150);
  }
}
