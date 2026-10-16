package com.xuri.training.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

public interface ResultMapper {
  @Select(
      "SELECT (SELECT COUNT(*) FROM course_enrollment WHERE user_id=#{user})"
          + " assigned_courses,(SELECT COUNT(*) FROM course_enrollment WHERE user_id=#{user} AND"
          + " completed=1) completed_courses,(SELECT COALESCE(SUM(effective_seconds),0) FROM"
          + " watch_progress WHERE user_id=#{user}) effective_seconds,(SELECT COUNT(*) FROM"
          + " exam_attempt WHERE user_id=#{user} AND status='GRADED' AND passed=1) passed_attempts")
  Map<String, Object> personal(long user);

  @Select(
      "SELECT COUNT(*) assigned_tasks,COALESCE(SUM(CASE WHEN e.completed=1 THEN 1 ELSE 0 END),0)"
          + " completed_tasks,COUNT(DISTINCT e.user_id) learner_count FROM course_enrollment e JOIN"
          + " app_user u ON u.id=e.user_id WHERE #{admin}=1 OR EXISTS(SELECT 1 FROM"
          + " manager_department m WHERE m.user_id=#{manager} AND m.department_id=u.department_id)")
  Map<String, Object> company(@Param("admin") boolean admin, @Param("manager") long manager);

  @Select(
      "SELECT d.id,d.name,COUNT(e.course_id) assigned_tasks,COALESCE(SUM(CASE WHEN e.completed=1"
          + " THEN 1 ELSE 0 END),0) completed_tasks FROM department d LEFT JOIN app_user u ON"
          + " u.department_id=d.id LEFT JOIN course_enrollment e ON e.user_id=u.id WHERE #{admin}=1"
          + " OR EXISTS(SELECT 1 FROM manager_department m WHERE m.user_id=#{manager} AND"
          + " m.department_id=d.id) GROUP BY d.id,d.name ORDER BY d.id")
  List<Map<String, Object>> departments(
      @Param("admin") boolean admin, @Param("manager") long manager);

  @Select(
      "SELECT u.id,u.display_name,u.department_id,e.course_id,c.title,e.completed,e.completed_at"
          + " FROM course_enrollment e JOIN app_user u ON u.id=e.user_id JOIN course c ON"
          + " c.id=e.course_id WHERE #{admin}=1 OR EXISTS(SELECT 1 FROM manager_department m WHERE"
          + " m.user_id=#{manager} AND m.department_id=u.department_id) ORDER BY u.id,c.id OFFSET"
          + " #{offset} ROWS FETCH NEXT 200 ROWS ONLY")
  List<Map<String, Object>> companyDetails(
      @Param("admin") boolean admin, @Param("manager") long manager, @Param("offset") int offset);

  @Select(
      "SELECT p.id,p.title,p.due_at,(SELECT COUNT(*) FROM plan_member m WHERE m.plan_id=p.id)"
          + " expected_people,(SELECT COUNT(*) FROM plan_member m WHERE m.plan_id=p.id AND NOT"
          + " EXISTS(SELECT 1 FROM plan_course pc LEFT JOIN course_enrollment e ON"
          + " e.course_id=pc.course_id AND e.user_id=m.user_id WHERE pc.plan_id=p.id AND"
          + " (e.completed IS NULL OR e.completed=0)) AND NOT EXISTS(SELECT 1 FROM plan_exam pe"
          + " WHERE pe.plan_id=p.id AND NOT EXISTS(SELECT 1 FROM exam_attempt a WHERE"
          + " a.exam_id=pe.exam_id AND a.user_id=m.user_id AND a.status='GRADED' AND a.passed=1)))"
          + " completed_people FROM training_plan p WHERE p.status='PUBLISHED' ORDER BY p.due_at")
  List<Map<String, Object>> planSummary();

  @Select(
      "SELECT c.id,c.title,c.status,COUNT(e.user_id) learner_count,COALESCE(SUM(CASE WHEN"
          + " e.completed=1 THEN 1 ELSE 0 END),0) completed_count FROM course c LEFT JOIN"
          + " course_enrollment e ON e.course_id=c.id WHERE c.owner_id=#{owner} GROUP BY"
          + " c.id,c.title,c.status ORDER BY c.id")
  List<Map<String, Object>> teaching(long owner);

  @Insert(
      "INSERT INTO assignment(course_id,title,instructions)"
          + " VALUES(#{course_id},#{title},#{instructions})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void assignment(Map<String, Object> a);

  @Select("SELECT * FROM assignment WHERE id=#{id}")
  Map<String, Object> assignmentById(long id);

  @Select("SELECT * FROM assignment WHERE course_id=#{course} ORDER BY id")
  List<Map<String, Object>> assignments(long course);

  @Insert(
      "IF EXISTS(SELECT 1 FROM assignment_submission WHERE assignment_id=#{assignment_id} AND"
          + " user_id=#{user_id}) UPDATE assignment_submission SET"
          + " content=#{content},asset_id=#{asset_id},score=NULL,feedback=NULL,grader_id=NULL WHERE"
          + " assignment_id=#{assignment_id} AND user_id=#{user_id} ELSE INSERT INTO"
          + " assignment_submission(assignment_id,user_id,content,asset_id)"
          + " VALUES(#{assignment_id},#{user_id},#{content},#{asset_id})")
  void submitAssignment(Map<String, Object> s);

  @Select(
      "SELECT s.*,a.course_id FROM assignment_submission s JOIN assignment a ON"
          + " a.id=s.assignment_id WHERE s.id=#{id}")
  Map<String, Object> submission(long id);

  @Select(
      "SELECT s.* FROM assignment_submission s JOIN assignment a ON a.id=s.assignment_id WHERE"
          + " a.course_id=#{course} AND (#{manager}=1 OR s.user_id=#{user}) ORDER BY s.id DESC")
  List<Map<String, Object>> submissions(
      @Param("course") long course, @Param("manager") boolean manager, @Param("user") long user);

  @Update(
      "UPDATE assignment_submission SET score=#{score},feedback=#{feedback},grader_id=#{grader}"
          + " WHERE id=#{id}")
  void gradeAssignment(Map<String, Object> s);

  @Insert(
      "IF EXISTS(SELECT 1 FROM course_feedback WHERE user_id=#{user} AND course_id=#{course})"
          + " UPDATE course_feedback SET rating=#{rating},comment=#{comment} WHERE user_id=#{user}"
          + " AND course_id=#{course} ELSE INSERT INTO"
          + " course_feedback(user_id,course_id,rating,comment)"
          + " VALUES(#{user},#{course},#{rating},#{comment})")
  void feedback(
      @Param("user") long user,
      @Param("course") long course,
      @Param("rating") int rating,
      @Param("comment") String comment);

  @Select("SELECT * FROM course_feedback WHERE course_id=#{course}")
  List<Map<String, Object>> feedbackList(long course);
}
