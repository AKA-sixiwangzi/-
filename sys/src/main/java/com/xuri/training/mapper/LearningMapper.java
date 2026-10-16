package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface LearningMapper {
  @Select("SELECT * FROM learning_session WHERE user_id=#{id} AND active=1")
  Map<String, Object> active(long id);

  @Select("SELECT * FROM learning_session WHERE id=#{id}")
  Map<String, Object> session(String id);

  @Update("UPDATE learning_session SET active=0 WHERE user_id=#{id} AND active=1")
  void stop(long id);

  @Insert(
      "INSERT INTO learning_session(id,user_id,lesson_id,active,last_at_ms)"
          + " VALUES(#{id},#{user_id},#{lesson_id},1,#{last_at_ms})")
  void start(Map<String, Object> s);

  @Update(
      "UPDATE learning_session SET"
          + " last_seq=#{seq},last_position=#{position},last_at_ms=#{now},playing=#{playing},active=#{active}"
          + " WHERE id=#{id}")
  void update(Map<String, Object> s);

  @Select("SELECT * FROM learning_event WHERE session_id=#{session} AND seq=#{seq}")
  Map<String, Object> event(@Param("session") String session, @Param("seq") int seq);

  @Insert(
      "INSERT INTO"
          + " learning_event(session_id,seq,event_type,position_seconds,accepted_seconds,reason)"
          + " VALUES(#{session},#{seq},#{type},#{position},#{accepted},#{reason})")
  void eventCreate(Map<String, Object> e);

  @Select(
      "SELECT COUNT(*) FROM learning_event WHERE session_id=#{id} AND event_type='FORWARD_SEEK' AND"
          + " received_at>=DATEADD(second,-60,SYSUTCDATETIME())")
  int recentSeeks(String id);

  @Insert(
      "INSERT INTO watch_segment(user_id,lesson_id,session_id,start_seconds,end_seconds)"
          + " VALUES(#{user},#{lesson},#{session},#{start},#{end})")
  void segment(Map<String, Object> s);

  @Select(
      "SELECT start_seconds,end_seconds FROM watch_segment WHERE user_id=#{user} AND"
          + " lesson_id=#{lesson} AND valid=1 ORDER BY start_seconds,end_seconds")
  List<Map<String, Object>> segments(@Param("user") long user, @Param("lesson") long lesson);

  @Insert(
      "IF NOT EXISTS(SELECT 1 FROM watch_progress WHERE user_id=#{user} AND lesson_id=#{lesson})"
          + " INSERT INTO watch_progress(user_id,lesson_id) VALUES(#{user},#{lesson})")
  void init(@Param("user") long user, @Param("lesson") long lesson);

  @Update(
      "UPDATE watch_progress SET"
          + " effective_seconds=#{seconds},covered_seconds=#{covered},completed=#{done} WHERE"
          + " user_id=#{user} AND lesson_id=#{lesson}")
  void progress(Map<String, Object> r);

  @Select(
      "SELECT l.id,l.title,l.duration_seconds,COALESCE(w.effective_seconds,0)"
          + " effective_seconds,COALESCE(w.covered_seconds,0)"
          + " covered_seconds,COALESCE(w.completed,0) completed FROM lesson l LEFT JOIN"
          + " watch_progress w ON w.lesson_id=l.id AND w.user_id=#{user} WHERE"
          + " l.course_id=#{course} ORDER BY l.sort_order")
  List<Map<String, Object>> progressList(@Param("user") long user, @Param("course") long course);

  @Update("UPDATE watch_segment SET valid=0 WHERE session_id=#{session}")
  int invalidate(String session);

  @Update(
      "UPDATE certificate SET status='REVOKED' WHERE user_id=#{user} AND EXISTS(SELECT 1 FROM"
          + " path_stage s JOIN stage_course c ON c.stage_id=s.id WHERE"
          + " s.path_id=certificate.path_id AND c.course_id=#{course}) AND EXISTS(SELECT 1 FROM"
          + " course_enrollment e WHERE e.user_id=#{user} AND e.course_id=#{course} AND"
          + " e.completed=0)")
  void revokeCertificates(@Param("user") long user, @Param("course") long course);
}
