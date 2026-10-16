package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface CourseMapper {
  @Select(
      "SELECT c.* FROM course c WHERE c.status='PUBLISHED' AND (#{admin}=1 OR EXISTS(SELECT 1 FROM"
          + " course_enrollment e WHERE e.course_id=c.id AND e.user_id=#{user})) AND (#{search}=''"
          + " OR c.title LIKE CONCAT('%',#{search},'%')) AND (#{category}='' OR"
          + " c.category=#{category}) ORDER BY c.id DESC OFFSET #{offset} ROWS FETCH NEXT 100 ROWS"
          + " ONLY")
  List<Map<String, Object>> catalog(
      @Param("offset") int offset,
      @Param("admin") boolean admin,
      @Param("user") long user,
      @Param("search") String search,
      @Param("category") String category);

  @Select("SELECT * FROM course WHERE owner_id=#{id} ORDER BY id DESC")
  List<Map<String, Object>> owned(long id);

  @Select("SELECT TOP(200) * FROM course WHERE status='PENDING' ORDER BY id")
  List<Map<String, Object>> pending();

  @Select("SELECT * FROM course WHERE id=#{id}")
  Map<String, Object> course(long id);

  @Select("SELECT * FROM course WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lockCourse(long id);

  @Insert(
      "INSERT INTO course(owner_id,title,description,category)"
          + " VALUES(#{owner_id},#{title},#{description},#{category})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void create(Map<String, Object> c);

  @Update(
      "UPDATE course SET"
          + " title=#{title},description=#{description},category=#{category},status='DRAFT',version=version+1"
          + " WHERE id=#{id}")
  void update(Map<String, Object> c);

  @Update(
      "UPDATE course SET status=#{status},reviewer_id=#{reviewer},review_note=#{note} WHERE"
          + " id=#{id}")
  void status(
      @Param("id") long id,
      @Param("status") String status,
      @Param("reviewer") Long reviewer,
      @Param("note") String note);

  @Insert(
      "INSERT INTO course_review(course_id,actor_id,action,note)"
          + " VALUES(#{course},#{actor},#{action},#{note})")
  void reviewLog(
      @Param("course") long course,
      @Param("actor") long actor,
      @Param("action") String action,
      @Param("note") String note);

  @Select("SELECT * FROM course_review WHERE course_id=#{id} ORDER BY id")
  List<Map<String, Object>> reviews(long id);

  @Select("SELECT * FROM lesson WHERE course_id=#{id} ORDER BY sort_order")
  List<Map<String, Object>> lessons(long id);

  @Select(
      "SELECT l.*,c.status AS course_status,c.owner_id FROM lesson l JOIN course c ON"
          + " c.id=l.course_id WHERE l.id=#{id}")
  Map<String, Object> lesson(long id);

  @Insert(
      "INSERT INTO"
          + " lesson(course_id,title,sort_order,duration_seconds,video_asset_id,coverage_threshold,time_threshold)"
          + " VALUES(#{course_id},#{title},#{sort_order},#{duration_seconds},#{video_asset_id},#{coverage_threshold},#{time_threshold})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void lessonCreate(Map<String, Object> l);

  @Select("SELECT * FROM course_material WHERE course_id=#{id} ORDER BY id")
  List<Map<String, Object>> materials(long id);

  @Insert(
      "INSERT INTO course_material(course_id,title,content,asset_id)"
          + " VALUES(#{course_id},#{title},#{content},#{asset_id})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void materialCreate(Map<String, Object> m);

  @Select("SELECT COUNT(*) FROM course_enrollment WHERE user_id=#{user} AND course_id=#{course}")
  int enrolled(@Param("user") long user, @Param("course") long course);

  @Select(
      "SELECT COUNT(*) FROM course_enrollment WHERE user_id=#{user} AND course_id=#{course} AND"
          + " completed=1")
  int completed(@Param("user") long user, @Param("course") long course);

  @Insert(
      "IF NOT EXISTS(SELECT 1 FROM course_enrollment WHERE user_id=#{user} AND course_id=#{course})"
          + " INSERT INTO course_enrollment(user_id,course_id) VALUES(#{user},#{course})")
  void enroll(@Param("user") long user, @Param("course") long course);

  @Update(
      "UPDATE course_enrollment SET completed=#{done},completed_at=CASE WHEN #{done}=1 THEN"
          + " COALESCE(completed_at,SYSUTCDATETIME()) ELSE NULL END WHERE user_id=#{user} AND"
          + " course_id=#{course}")
  void completion(
      @Param("user") long user, @Param("course") long course, @Param("done") boolean done);

  @Select(
      "SELECT c.id,c.title,e.completed,e.enrolled_at,e.completed_at FROM course_enrollment e JOIN"
          + " course c ON c.id=e.course_id WHERE e.user_id=#{id} ORDER BY e.enrolled_at DESC")
  List<Map<String, Object>> myLearning(long id);

  @Select(
      "SELECT"
          + " u.id,u.display_name,u.department_id,e.completed,e.completed_at,COALESCE(SUM(w.effective_seconds),0)"
          + " AS effective_seconds FROM course_enrollment e JOIN app_user u ON u.id=e.user_id LEFT"
          + " JOIN lesson l ON l.course_id=e.course_id LEFT JOIN watch_progress w ON"
          + " w.lesson_id=l.id AND w.user_id=u.id WHERE e.course_id=#{course} GROUP BY"
          + " u.id,u.display_name,u.department_id,e.completed,e.completed_at ORDER BY u.id")
  List<Map<String, Object>> learners(long course);

  @Insert(
      "INSERT INTO file_asset(id,owner_id,name,storage_name,content_type,size_bytes)"
          + " VALUES(#{id},#{owner_id},#{name},#{storage_name},#{content_type},#{size_bytes})")
  void file(Map<String, Object> f);

  @Select("SELECT * FROM file_asset WHERE id=#{id}")
  Map<String, Object> asset(String id);

  @Insert(
      "INSERT INTO media_ticket(token_hash,asset_id,user_id,expires_at)"
          + " VALUES(#{hash},#{asset},#{user},DATEADD(minute,15,SYSUTCDATETIME()))")
  void ticket(@Param("hash") String hash, @Param("asset") String asset, @Param("user") long user);

  @Select(
      "SELECT t.user_id FROM media_ticket t JOIN app_user u ON u.id=t.user_id WHERE"
          + " t.token_hash=#{hash} AND t.asset_id=#{asset} AND t.expires_at>SYSUTCDATETIME() AND"
          + " u.active=1")
  Long mediaUser(@Param("hash") String hash, @Param("asset") String asset);

  @Select(
      "SELECT COUNT(*) FROM file_asset a WHERE a.id=#{asset} AND (a.owner_id=#{user} OR"
          + " EXISTS(SELECT 1 FROM lesson l JOIN course c ON c.id=l.course_id JOIN"
          + " course_enrollment e ON e.course_id=c.id WHERE l.video_asset_id=a.id AND"
          + " c.status='PUBLISHED' AND e.user_id=#{user}) OR EXISTS(SELECT 1 FROM course_material m"
          + " JOIN course c ON c.id=m.course_id JOIN course_enrollment e ON e.course_id=c.id WHERE"
          + " m.asset_id=a.id AND c.status='PUBLISHED' AND e.user_id=#{user}) OR EXISTS(SELECT 1"
          + " FROM assignment_submission s JOIN assignment t ON t.id=s.assignment_id JOIN course c"
          + " ON c.id=t.course_id WHERE s.asset_id=a.id AND c.owner_id=#{user}))")
  int readableAsset(@Param("asset") String asset, @Param("user") long user);
}
