package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface ProgramMapper {
  @Insert(
      "INSERT INTO learning_path(title,position_id,grade_id)"
          + " VALUES(#{title},#{position_id},#{grade_id})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void createPath(Map<String, Object> r);

  @Select("SELECT * FROM learning_path WHERE id=#{id}")
  Map<String, Object> path(long id);

  @Select("SELECT * FROM learning_path WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lockPath(long id);

  @Select(
      "SELECT p.* FROM learning_path p WHERE #{admin}=1 OR EXISTS(SELECT 1 FROM path_assignment a"
          + " WHERE a.path_id=p.id AND a.user_id=#{user}) ORDER BY p.id DESC")
  List<Map<String, Object>> paths(@Param("admin") boolean admin, @Param("user") long user);

  @Insert(
      "INSERT INTO path_stage(path_id,title,sort_order) VALUES(#{path_id},#{title},#{sort_order})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void stage(Map<String, Object> r);

  @Select("SELECT * FROM path_stage WHERE id=#{id}")
  Map<String, Object> stageById(long id);

  @Select("SELECT * FROM path_stage WHERE path_id=#{id} ORDER BY sort_order")
  List<Map<String, Object>> stages(long id);

  @Insert("INSERT INTO stage_course(stage_id,course_id) VALUES(#{stage},#{course})")
  void stageCourse(@Param("stage") long stage, @Param("course") long course);

  @Select(
      "SELECT c.id,c.title,COALESCE(e.completed,0) completed FROM stage_course sc JOIN course c ON"
          + " c.id=sc.course_id LEFT JOIN course_enrollment e ON e.course_id=c.id AND"
          + " e.user_id=#{user} WHERE sc.stage_id=#{stage} ORDER BY c.id")
  List<Map<String, Object>> stageCourses(@Param("stage") long stage, @Param("user") long user);

  @Update("UPDATE learning_path SET published=1 WHERE id=#{id}")
  void publishPath(long id);

  @Select("SELECT COUNT(*) FROM path_assignment WHERE path_id=#{path} AND user_id=#{user}")
  int assigned(@Param("path") long path, @Param("user") long user);

  @Insert(
      "IF NOT EXISTS(SELECT 1 FROM path_assignment WHERE path_id=#{path} AND user_id=#{user})"
          + " INSERT INTO path_assignment(user_id,path_id) VALUES(#{user},#{path})")
  void assign(@Param("path") long path, @Param("user") long user);

  @Select(
      "SELECT e.id,e.title,CASE WHEN EXISTS(SELECT 1 FROM exam_attempt a WHERE a.exam_id=e.id AND"
          + " a.user_id=#{user} AND a.status='GRADED' AND a.passed=1) THEN 1 ELSE 0 END passed FROM"
          + " exam e WHERE e.stage_id=#{stage}")
  List<Map<String, Object>> stageExams(@Param("stage") long stage, @Param("user") long user);

  @Insert(
      "INSERT INTO training_plan(title,start_at,due_at,owner_id)"
          + " VALUES(#{title},#{start_at},#{due_at},#{owner_id})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void plan(Map<String, Object> p);

  @Select("SELECT * FROM training_plan WITH(UPDLOCK,HOLDLOCK) WHERE id=#{id}")
  Map<String, Object> lockPlan(long id);

  @Select(
      "SELECT p.* FROM training_plan p WHERE #{admin}=1 OR EXISTS(SELECT 1 FROM plan_member m WHERE"
          + " m.plan_id=p.id AND m.user_id=#{user}) ORDER BY p.due_at")
  List<Map<String, Object>> plans(@Param("admin") boolean admin, @Param("user") long user);

  @Insert("INSERT INTO plan_course(plan_id,course_id) VALUES(#{plan},#{course})")
  void planCourse(@Param("plan") long plan, @Param("course") long course);

  @Insert("INSERT INTO plan_member(plan_id,user_id) VALUES(#{plan},#{user})")
  void planMember(@Param("plan") long plan, @Param("user") long user);

  @Select("SELECT course_id FROM plan_course WHERE plan_id=#{plan}")
  List<Long> planCourses(long plan);

  @Select("SELECT user_id FROM plan_member WHERE plan_id=#{plan}")
  List<Long> planMembers(long plan);

  @Insert("INSERT INTO plan_exam(plan_id,exam_id) VALUES(#{plan},#{exam})")
  void planExam(@Param("plan") long plan, @Param("exam") long exam);

  @Select("SELECT exam_id FROM plan_exam WHERE plan_id=#{plan}")
  List<Long> planExams(long plan);

  @Select(
      "SELECT e.id,e.title,CASE WHEN EXISTS(SELECT 1 FROM exam_attempt a WHERE a.exam_id=e.id AND"
          + " a.user_id=#{user} AND a.status='GRADED' AND a.passed=1) THEN 1 ELSE 0 END passed FROM"
          + " plan_exam p JOIN exam e ON e.id=p.exam_id WHERE p.plan_id=#{plan}")
  List<Map<String, Object>> planExamProgress(@Param("plan") long plan, @Param("user") long user);

  @Update("UPDATE training_plan SET status='PUBLISHED' WHERE id=#{id}")
  void publishPlan(long id);

  @Select(
      "SELECT c.id,c.title,COALESCE(e.completed,0) completed FROM plan_course pc JOIN course c ON"
          + " c.id=pc.course_id LEFT JOIN course_enrollment e ON e.course_id=c.id AND"
          + " e.user_id=#{user} WHERE pc.plan_id=#{plan}")
  List<Map<String, Object>> planProgress(@Param("plan") long plan, @Param("user") long user);

  @Select("SELECT * FROM certificate WHERE user_id=#{user} ORDER BY issued_at DESC")
  List<Map<String, Object>> certificates(long user);

  @Insert(
      "IF NOT EXISTS(SELECT 1 FROM certificate WHERE user_id=#{user} AND path_id=#{path}) INSERT"
          + " INTO certificate(id,user_id,path_id) VALUES(NEWID(),#{user},#{path}) ELSE UPDATE"
          + " certificate SET status='VALID',issued_at=CASE WHEN status='REVOKED' THEN"
          + " SYSUTCDATETIME() ELSE issued_at END WHERE user_id=#{user} AND path_id=#{path}")
  void certificate(@Param("user") long user, @Param("path") long path);

  @Select(
      "SELECT TOP(500) p.id,p.title,m.user_id FROM training_plan p JOIN plan_member m ON"
          + " m.plan_id=p.id WHERE p.status='PUBLISHED' AND"
          + " p.due_at<DATEADD(day,3,SYSUTCDATETIME()) AND (EXISTS(SELECT 1 FROM plan_course pc"
          + " LEFT JOIN course_enrollment e ON e.course_id=pc.course_id AND e.user_id=m.user_id"
          + " WHERE pc.plan_id=p.id AND (e.completed IS NULL OR e.completed=0)) OR EXISTS(SELECT 1"
          + " FROM plan_exam pe WHERE pe.plan_id=p.id AND NOT EXISTS(SELECT 1 FROM exam_attempt a"
          + " WHERE a.exam_id=pe.exam_id AND a.user_id=m.user_id AND a.status='GRADED' AND"
          + " a.passed=1))) AND NOT EXISTS(SELECT 1 FROM notification n WHERE n.user_id=m.user_id"
          + " AND n.title=CONCAT(N'培训到期提醒 #',p.id) AND"
          + " n.created_at>DATEADD(day,-1,SYSUTCDATETIME())) ORDER BY p.due_at")
  List<Map<String, Object>> reminders();
}
