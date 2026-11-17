package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface OfflineMapper {
  @Select(
      "SELECT s.*,i.display_name AS instructor_name,c.title AS course_title,p.title AS plan_title,"
          + " (SELECT COUNT(*) FROM offline_attendance all_a WHERE all_a.session_id=s.id) member_count,"
          + " a.status AS attendance_status,a.score,a.note,a.recorded_at"
          + " FROM offline_session s JOIN app_user i ON i.id=s.instructor_id"
          + " LEFT JOIN course c ON c.id=s.course_id LEFT JOIN training_plan p ON p.id=s.plan_id"
          + " LEFT JOIN offline_attendance a ON a.session_id=s.id AND a.user_id=#{user}"
          + " WHERE #{admin}=1 OR (#{instructor}=1 AND s.instructor_id=#{user}) OR a.user_id IS NOT NULL"
          + " ORDER BY s.start_at DESC,s.id DESC")
  List<Map<String, Object>> list(
      @Param("admin") boolean admin, @Param("instructor") boolean instructor, @Param("user") long user);

  @Select(
      "SELECT s.*,i.display_name AS instructor_name,c.title AS course_title,p.title AS plan_title"
          + " FROM offline_session s JOIN app_user i ON i.id=s.instructor_id"
          + " LEFT JOIN course c ON c.id=s.course_id LEFT JOIN training_plan p ON p.id=s.plan_id"
          + " WHERE s.id=#{id}")
  Map<String, Object> session(long id);

  @Select("SELECT * FROM offline_session WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lockSession(long id);

  @Select(
      "SELECT a.user_id,u.id,u.display_name,u.department_id,u.position_id,a.status,a.score,a.note,"
          + " a.recorded_by,a.recorded_at FROM offline_attendance a JOIN app_user u ON u.id=a.user_id"
          + " WHERE a.session_id=#{session} AND (#{all}=1 OR a.user_id=#{user}) ORDER BY u.id")
  List<Map<String, Object>> attendance(
      @Param("session") long session, @Param("all") boolean all, @Param("user") long user);

  @Select("SELECT * FROM offline_attendance WHERE session_id=#{session} AND user_id=#{user}")
  Map<String, Object> attendanceRow(@Param("session") long session, @Param("user") long user);

  @Insert(
      "INSERT INTO offline_session(title,plan_id,course_id,instructor_id,start_at,end_at,location,created_by)"
          + " VALUES(#{title},#{plan_id},#{course_id},#{instructor_id},#{start_at},#{end_at},#{location},#{created_by})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void create(Map<String, Object> session);

  @Insert("INSERT INTO offline_attendance(session_id,user_id) VALUES(#{session},#{user})")
  void register(@Param("session") long session, @Param("user") long user);

  @Update(
      "UPDATE offline_attendance SET status=#{status},score=#{score},note=#{note},"
          + " recorded_by=#{recorded_by},recorded_at=SYSUTCDATETIME()"
          + " WHERE session_id=#{session_id} AND user_id=#{user_id}")
  int record(Map<String, Object> attendance);

  @Select(
      "SELECT u.id,u.display_name,u.department_id,u.position_id,u.active FROM app_user u"
          + " WHERE u.active=1 AND (#{admin}=1 OR EXISTS(SELECT 1 FROM course_enrollment e"
          + " JOIN course c ON c.id=e.course_id WHERE e.user_id=u.id AND c.owner_id=#{owner}"
          + " AND c.status='PUBLISHED')) ORDER BY u.id")
  List<Map<String, Object>> eligibleUsers(@Param("admin") boolean admin, @Param("owner") long owner);

  @Select(
      "SELECT e.user_id,e.course_id FROM course_enrollment e JOIN course c ON c.id=e.course_id"
          + " WHERE c.status='PUBLISHED' AND (#{admin}=1 OR c.owner_id=#{owner})")
  List<Map<String, Object>> eligibleEnrollments(@Param("admin") boolean admin, @Param("owner") long owner);

  @Select(
      "SELECT u.id,u.display_name FROM app_user u WHERE u.active=1"
          + " AND (#{admin}=1 OR u.id=#{owner}) AND EXISTS(SELECT 1 FROM user_role r"
          + " WHERE r.user_id=u.id AND r.role='INSTRUCTOR') ORDER BY u.id")
  List<Map<String, Object>> instructors(@Param("admin") boolean admin, @Param("owner") long owner);

  @Select(
      "SELECT id,title FROM course WHERE status='PUBLISHED' AND (#{admin}=1 OR owner_id=#{owner})"
          + " ORDER BY id DESC")
  List<Map<String, Object>> eligibleCourses(@Param("admin") boolean admin, @Param("owner") long owner);

  @Select(
      "SELECT p.id,p.title FROM training_plan p WHERE p.status='PUBLISHED'"
          + " AND (#{admin}=1 OR COALESCE(p.responsible_user_id,p.owner_id)=#{owner} OR EXISTS"
          + " (SELECT 1 FROM plan_course pc JOIN course c ON c.id=pc.course_id"
          + " WHERE pc.plan_id=p.id AND c.owner_id=#{owner})) ORDER BY p.id DESC")
  List<Map<String, Object>> eligiblePlans(@Param("admin") boolean admin, @Param("owner") long owner);

  @Select(
      "SELECT u.id FROM plan_member m JOIN app_user u ON u.id=m.user_id WHERE m.plan_id=#{plan}"
          + " AND u.active=1 AND (#{admin}=1 OR EXISTS(SELECT 1 FROM course_enrollment e"
          + " JOIN course c ON c.id=e.course_id WHERE e.user_id=u.id AND c.owner_id=#{owner}"
          + " AND c.status='PUBLISHED')) ORDER BY u.id")
  List<Long> planUsers(
      @Param("plan") long plan, @Param("admin") boolean admin, @Param("owner") long owner);

  @Select(
      "SELECT pc.course_id FROM plan_course pc JOIN course c ON c.id=pc.course_id"
          + " WHERE pc.plan_id=#{plan} AND c.status='PUBLISHED'"
          + " AND (#{admin}=1 OR c.owner_id=#{owner}) ORDER BY pc.course_id")
  List<Long> planCourses(
      @Param("plan") long plan, @Param("admin") boolean admin, @Param("owner") long owner);

  @Select(
      "SELECT COUNT(*) FROM course_enrollment e JOIN course c ON c.id=e.course_id"
          + " WHERE e.user_id=#{user} AND c.owner_id=#{owner} AND c.status='PUBLISHED'")
  int ownedLearner(@Param("owner") long owner, @Param("user") long user);

  @Select("SELECT * FROM training_plan WITH(HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> plan(long id);

  @Select("SELECT COUNT(*) FROM training_plan p WHERE p.id=#{plan} AND"
      + " (COALESCE(p.responsible_user_id,p.owner_id)=#{owner} OR EXISTS"
      + " (SELECT 1 FROM plan_course pc JOIN course c ON c.id=pc.course_id"
      + " WHERE pc.plan_id=p.id AND c.owner_id=#{owner}))")
  int authorizedPlan(@Param("plan") long plan, @Param("owner") long owner);

  @Select("SELECT COUNT(*) FROM plan_member WHERE plan_id=#{plan} AND user_id=#{user}")
  int planMember(@Param("plan") long plan, @Param("user") long user);

  @Select("SELECT COUNT(*) FROM plan_course WHERE plan_id=#{plan} AND course_id=#{course}")
  int planCourse(@Param("plan") long plan, @Param("course") long course);
}
