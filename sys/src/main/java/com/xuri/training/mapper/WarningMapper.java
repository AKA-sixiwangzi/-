package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface WarningMapper {
  @Select(
      "SELECT TOP(1) id FROM warning WITH(UPDLOCK,HOLDLOCK) WHERE user_id=#{user} AND type=#{type}"
          + " AND status<>'CLOSED' AND created_at>DATEADD(minute,-5,SYSUTCDATETIME()) ORDER BY id"
          + " DESC")
  Long recent(@Param("user") long user, @Param("type") String type);

  @Update("UPDATE warning SET occurrences=occurrences+1,updated_at=SYSUTCDATETIME() WHERE id=#{id}")
  void repeat(long id);

  @Insert(
      "INSERT INTO warning(user_id,course_id,session_id,type,severity,evidence)"
          + " VALUES(#{user},#{course},#{session},#{type},#{severity},#{evidence})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void create(Map<String, Object> r);

  @Select("SELECT TOP(200) * FROM warning WHERE #{admin}=1 OR user_id=#{user} ORDER BY id DESC")
  List<Map<String, Object>> list(@Param("admin") boolean admin, @Param("user") long user);

  @Select("SELECT * FROM warning WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lock(long id);

  @Select("SELECT * FROM warning WHERE id=#{id}")
  Map<String, Object> get(long id);

  @Select(
      "SELECT a.*,u.display_name FROM warning_action a JOIN app_user u ON u.id=a.actor_id WHERE"
          + " warning_id=#{id} ORDER BY a.id")
  List<Map<String, Object>> actions(long id);

  @Update(
      "UPDATE warning SET status=#{state},handler_id=#{actor},updated_at=SYSUTCDATETIME() WHERE"
          + " id=#{id}")
  void state(@Param("id") long id, @Param("state") String state, @Param("actor") long actor);

  @Insert(
      "INSERT INTO warning_action(warning_id,actor_id,action,note,before_state,after_state)"
          + " VALUES(#{warning},#{actor},#{action},#{note},#{before},#{after})")
  void action(Map<String, Object> r);

  @Insert("INSERT INTO notification(user_id,title,body) VALUES(#{user},#{title},#{body})")
  void notify(@Param("user") long user, @Param("title") String title, @Param("body") String body);

  @Select("SELECT TOP(100) * FROM notification WHERE user_id=#{user} ORDER BY id DESC")
  List<Map<String, Object>> notifications(long user);

  @Update(
      "UPDATE notification SET read_at=COALESCE(read_at,SYSUTCDATETIME()) WHERE id=#{id} AND"
          + " user_id=#{user}")
  int read(@Param("id") long id, @Param("user") long user);
}
