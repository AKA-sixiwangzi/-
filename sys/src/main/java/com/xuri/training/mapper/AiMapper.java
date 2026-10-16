package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface AiMapper {
  @Select(
      "SELECT COUNT(*) FROM ai_request WITH(UPDLOCK,HOLDLOCK) WHERE user_id=#{user} AND"
          + " created_at>DATEADD(minute,-10,SYSUTCDATETIME())")
  int recent(long user);

  @Insert(
      "INSERT INTO ai_request(user_id,course_id,purpose,status)"
          + " VALUES(#{user},#{course},#{purpose},#{status})")
  void request(Map<String, Object> r);
}
